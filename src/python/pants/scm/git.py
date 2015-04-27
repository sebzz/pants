# coding=utf-8
# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import (absolute_import, division, generators, nested_scopes, print_function,
                        unicode_literals, with_statement)

import os
import StringIO
import subprocess
import traceback
from collections import defaultdict
from contextlib import contextmanager

from pants.scm.scm import Scm
from pants.util.strutil import ensure_binary


class Git(Scm):
  """An Scm implementation backed by git."""

  @classmethod
  def detect_worktree(cls, binary='git', dir=None):
    """Detect the git working tree above cwd and return it; else, return None.

    binary: The path to the git binary to use, 'git' by default.
    """
    # TODO(John Sirois): This is only used as a factory for a Git instance in
    # pants.base.build_environment.get_scm, encapsulate in a true factory method.
    cmd = [binary, 'rev-parse', '--show-toplevel']
    try:
      if dir:
        with pushd(dir):
          process, out = cls._invoke(cmd)
      else:
        process, out = cls._invoke(cmd)
      cls._check_result(cmd, process.returncode, raise_type=Scm.ScmException)
    except Scm.ScmException:
      return None
    return cls._cleanse(out)

  @classmethod
  def _invoke(cls, cmd):
    """Invoke the given command, and return a tuple of process and raw binary output.

    stderr flows to wherever its currently mapped for the parent process - generally to
    the terminal where the user can see the error.

    :param list cmd: The command in the form of a list of strings
    :returns: The completed process object and its standard output.
    :raises: Scm.LocalException if there was a problem exec'ing the command at all.
    """
    try:
      process = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    except OSError as e:
      # Binary DNE or is not executable
      raise cls.LocalException('Failed to execute command {}: {}'.format(' '.join(cmd), e))
    out, _ = process.communicate()
    return process, out

  @classmethod
  def _cleanse(cls, output, errors='strict'):
    return output.strip().decode('utf-8', errors=errors)

  @classmethod
  def _check_result(cls, cmd, result, failure_msg=None, raise_type=Scm.ScmException):
    if result != 0:
      raise raise_type(failure_msg or '{} failed with exit code {}'.format(' '.join(cmd), result))

  def __init__(self, binary='git', gitdir=None, worktree=None, remote=None, branch=None, log=None):
    """Creates a git scm proxy that assumes the git repository is in the cwd by default.

    binary:    The path to the git binary to use, 'git' by default.
    gitdir:    The path to the repository's git metadata directory (typically '.git').
    worktree:  The path to the git repository working tree directory (typically '.').
    remote:    The default remote to use.
    branch:    The default remote branch to use.
    log:       A log object that supports debug, info, and warn methods.
    """
    super(Scm, self).__init__()

    self._gitcmd = binary
    self._worktree = os.path.realpath(worktree or os.getcwd())
    self._gitdir = os.path.realpath(gitdir) if gitdir else os.path.join(self._worktree, '.git')
    self._remote = remote
    self._branch = branch

    if log:
      self._log = log
    else:
      import logging
      self._log = logging.getLogger(__name__)

    self._cat_file_process = None
    # Trees is a dict from rev -> dict(path=[list of Dir, Symlink or File objects])
    self._trees = defaultdict(dict)

  def current_rev_identifier(self):
    return 'HEAD'

  @property
  def commit_id(self):
    return self._check_output(['rev-parse', 'HEAD'], raise_type=Scm.LocalException)

  @property
  def server_url(self):
    git_output = self._check_output(['remote', '--verbose'], raise_type=Scm.LocalException)
    origin_push_line = [line.split()[1] for line in git_output.splitlines()
                                        if 'origin' in line and '(push)' in line]
    if len(origin_push_line) != 1:
      raise Scm.LocalException('Unable to find origin remote amongst: ' + git_output)
    return origin_push_line[0]

  @property
  def tag_name(self):
    tag = self._check_output(['describe', '--tags', '--always'], raise_type=Scm.LocalException)
    return None if b'cannot' in tag else tag

  @property
  def branch_name(self):
    branch = self._check_output(['rev-parse', '--abbrev-ref', 'HEAD'],
                                raise_type=Scm.LocalException)
    return None if branch == 'HEAD' else branch

  def fix_git_relative_path(self, worktree_path, relative_to):
    return os.path.relpath(os.path.join(self._worktree, worktree_path), relative_to)

  def changed_files(self, from_commit=None, include_untracked=False, relative_to=None):
    relative_to = relative_to or self._worktree
    rel_suffix = ['--', relative_to]
    uncommitted_changes = self._check_output(['diff', '--name-only', 'HEAD'] + rel_suffix,
                                             raise_type=Scm.LocalException)

    files = set(uncommitted_changes.split())
    if from_commit:
      # Grab the diff from the merge-base to HEAD using ... syntax.  This ensures we have just
      # the changes that have occurred on the current branch.
      committed_cmd = ['diff', '--name-only', from_commit + '...HEAD'] + rel_suffix
      committed_changes = self._check_output(committed_cmd,
                                             raise_type=Scm.LocalException)
      files.update(committed_changes.split())
    if include_untracked:
      untracked_cmd = ['ls-files', '--other', '--exclude-standard'] + rel_suffix
      untracked = self._check_output(untracked_cmd,
                                     raise_type=Scm.LocalException)
      files.update(untracked.split())
    # git will report changed files relative to the worktree: re-relativize to relative_to
    return set(self.fix_git_relative_path(f, relative_to) for f in files)

  def changes_in(self, diffspec, relative_to=None):
    relative_to = relative_to or self._worktree
    cmd = ['diff-tree', '--no-commit-id', '--name-only', '-r', diffspec]
    files = self._check_output(cmd, raise_type=Scm.LocalException).split()
    return set(self.fix_git_relative_path(f.strip(), relative_to) for f in files)

  def changelog(self, from_commit=None, files=None):
    # We force the log output encoding to be UTF-8 here since the user may have a git config that
    # overrides the git UTF-8 default log output encoding.
    args = ['log', '--encoding=UTF-8', '--no-merges', '--stat', '--find-renames', '--find-copies']
    if from_commit:
      args.append(from_commit + '..HEAD')
    if files:
      args.append('--')
      args.extend(files)

    # There are various circumstances that can lead to git logs that are not transcodeable to utf-8,
    # for example: http://comments.gmane.org/gmane.comp.version-control.git/262685
    # Git will not error in these cases and we do not wish to either.  Here we direct byte sequences
    # that can not be utf-8 decoded to be replaced with the utf-8 replacement character.
    return self._check_output(args, raise_type=Scm.LocalException, errors='replace')

  def merge_base(self, left='master', right='HEAD'):
    """Returns the merge-base of master and HEAD in bash: `git merge-base left right`"""
    return self._check_output(['merge-base', left, right], raise_type=Scm.LocalException)

  def refresh(self, leave_clean=False):
    """Attempt to pull-with-rebase from upstream.  This is implemented as fetch-plus-rebase
       so that we can distinguish between errors in the fetch stage (likely network errors)
       and errors in the rebase stage (conflicts).  If leave_clean is true, then in the event
       of a rebase failure, the branch will be rolled back.  Otherwise, it will be left in the
       conflicted state.
    """
    remote, merge = self._get_upstream()
    self._check_call(['fetch', '--tags', remote, merge], raise_type=Scm.RemoteException)
    try:
      self._check_call(['rebase', 'FETCH_HEAD'], raise_type=Scm.LocalException)
    except Scm.LocalException as e:
      if leave_clean:
        self._log.debug('Cleaning up after failed rebase')
        try:
          self._check_call(['rebase', '--abort'], raise_type=Scm.LocalException)
        except Scm.LocalException as abort_exc:
          self._log.debug('Failed to up after failed rebase')
          self._log.debug(traceback.format_exc(abort_exc))
          # But let the original exception propagate, since that's the more interesting one
      raise e

  def tag(self, name, message=None):
    # We use -a here instead of --annotate to maintain maximum git compatibility.
    # --annotate was only introduced in 1.7.8 via:
    #   https://github.com/git/git/commit/c97eff5a95d57a9561b7c7429e7fcc5d0e3a7f5d
    self._check_call(['tag', '-a', '--message=' + (message or ''), name],
                     raise_type=Scm.LocalException)
    self.push('refs/tags/' + name)

  def commit(self, message):
    self._check_call(['commit', '--all', '--message=' + message], raise_type=Scm.LocalException)

  def add(self, *paths):
    self._check_call(['add'] + list(paths), raise_type=Scm.LocalException)


  def commit_date(self, commit_reference):
    return self._check_output(['log', '-1', '--pretty=tformat:%ci', commit_reference],
                              raise_type=Scm.LocalException)

  def push(self, *refs):
    remote, merge = self._get_upstream()
    self._check_call(['push', remote, merge] + list(refs), raise_type=Scm.RemoteException)

  def _get_upstream(self):
    """Return the remote and remote merge branch for the current branch"""
    if not self._remote or not self._branch:
      branch = self.branch_name
      if not branch:
        raise Scm.LocalException('Failed to determine local branch')

      def get_local_config(key):
        value = self._check_output(['config', '--local', '--get', key],
                                   raise_type=Scm.LocalException)
        return value.strip()

      self._remote = self._remote or get_local_config('branch.{}.remote'.format(branch))
      self._branch = self._branch or get_local_config('branch.{}.merge'.format(branch))
    return self._remote, self._branch

  def _check_call(self, args, failure_msg=None, raise_type=None):
    cmd = self._create_git_cmdline(args)
    self._log_call(cmd)
    result = subprocess.call(cmd)
    self._check_result(cmd, result, failure_msg, raise_type)

  def _check_output(self, args, failure_msg=None, raise_type=None, errors='strict'):
    cmd = self._create_git_cmdline(args)
    self._log_call(cmd)

    process, out = self._invoke(cmd)

    self._check_result(cmd, process.returncode, failure_msg, raise_type)
    return self._cleanse(out, errors=errors)

  def _create_git_cmdline(self, args):
    return [self._gitcmd, '--git-dir=' + self._gitdir, '--work-tree=' + self._worktree] + args

  def _log_call(self, cmd):
    self._log.debug('Executing: ' + ' '.join(cmd))

  def _maybe_start_cat_file_process(self):
    if not self._cat_file_process:
      self._cat_file_process = subprocess.Popen(self._create_git_cmdline(['cat-file', '--batch']),
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE)

  class MissingFileException(Exception):
    def __init__(self, rev, relpath):
      self.relpath = relpath
      self.rev = rev

    def __str__(self):
      return "MissingFileException({}, {})".format(self.relpath, self.rev)

  class IsDirException(Exception):
    def __init__(self, rev, relpath):
      self.relpath = relpath
      self.rev = rev

    def __str__(self):
      return "IsDirException({}, {})".format(self.relpath, self.rev)

  class NotADirException(Exception):
    def __init__(self, rev, relpath):
      self.relpath = relpath
      self.rev = rev

    def __str__(self):
      return "NotADirException({}, {})".format(self.relpath, self.rev)

  class SymlinkLoopException(Exception):
    def __init__(self, rev, relpath):
      self.relpath = relpath
      self.rev = rev

    def __str__(self):
      return "SymlinkLoop({}, {})".format(self.relpath, self.rev)

  class BadModeException(Exception):
    pass

  class GitDiedException(Exception):
    pass

  def exists(self, rev, relpath):
    try:
      self._realpath(rev, relpath)
      return True
    except self.MissingFileException:
      return False
    except self.NotADirException:
      return False

  def isfile(self, rev, relpath):
    try:
      path = self._realpath(rev, relpath)
      return not path.endswith('/')
    except self.MissingFileException:
      return False
    except self.NotADirException:
      return False

  def isdir(self, rev, relpath):
    try:
      path = self._realpath(rev, relpath)
      return path.endswith('/')
    except self.MissingFileException:
      return False
    except self.NotADirException:
      return False

  class Symlink:
    def __init__(self, name, sha):
      self.name = name
      self.sha = sha

  class Dir:
    def __init__(self, name, sha):
      self.name = name
      self.sha = sha

  class File:
    def __init__(self, name, sha):
      self.name = name
      self.sha = sha

  def listdir(self, rev, relpath):
    """Like os.listdir, but reads from the git repository.

    :returns: a list of relative filenames
    """

    path = self._realpath(rev, relpath)
    if not path.endswith('/'):
      raise self.NotADirException(rev, relpath)

    tree = self._read_tree(rev, path[:-1])
    return tree.keys()

  @contextmanager
  def open(self, rev, relpath, mode='rb'):
    """Read a file out of the repository at a certain revision.

    This is complicated because, unlike vanilla git cat-file, this follows symlinks in
    the repo.  If a symlink points outside repo, the file is read from the filesystem;
    that's because presumably whoever put that symlink there knew what they were doing.
    """

    if mode != 'rb':
      raise self.BadModeException(rev, relpath, mode)

    path = self._realpath(rev, relpath)
    if path.endswith('/'):
      raise self.IsDirException(rev, relpath)

    if path.startswith('../') or path[0] == '/':
      yield open(path, mode)

    object_type, data = self._read_object_from_repo(rev=rev, relpath=path)
    if object_type == 'tree':
      raise self.IsDirException(rev, relpath)
    assert object_type == 'blob'
    yield StringIO.StringIO(data)

  def _realpath(self, rev, relpath):
    """Follow symlinks to find the real path to a file or directory in the repo.

    :returns: if the expanded path points to a file, the relative path
              to that file; if a directory, the relative path + '/'; if
              a symlink outside the repo, a path starting with / or ../.
    """
    self._maybe_start_cat_file_process()

    if relpath == '.' or relpath == '':
      # The root is never a symlink, so we don't need to process it further
      return '/'

    path_so_far = ''
    components = list(relpath.split(os.path.sep))
    symlinks = 0

    while components:
      component = components.pop(0)
      if component == '':
        continue

      parent_tree = self._read_tree(rev, path_so_far)
      parent_path = path_so_far

      if path_so_far:
        path_so_far += '/'
      path_so_far += component

      try:
        obj = parent_tree[component]
      except KeyError:
        raise self.MissingFileException(rev, relpath)

      if isinstance(obj, self.File):
        if components:
          # We've encountered a file while searching for a directory
          raise self.NotADirException(rev, relpath)
        else:
          return path_so_far
      elif isinstance(obj, self.Dir):
        if not components:
          return path_so_far + '/'
        # A dir is OK; we just descend from here
      elif isinstance(obj, self.Symlink):
        # 40 is Linux's hard-coded limit for total symlinks followed when resolving a path.
        symlinks += 1
        if symlinks > 40:
          raise self.SymlinkLoopException(rev, relpath)
        # A git symlink is stored as a blob containing the name of the target.
        # Read that blob.
        object_type, path_data = self._read_object_from_repo(sha=obj.sha)
        assert object_type == 'blob'

        link_to = os.path.normpath(os.path.join(parent_path, path_data))
        if link_to.startswith('../') or link_to[0] == '/':
          # If the link points outside the repo, then just return that file
          return link_to

        # Restart our search at the top with the new path.
        # Git stores symlinks in terms of Unix paths, so split on '/' instead of os.path.sep
        components = link_to.split(ensure_binary('/')) + components
        path_so_far = ''
      else:
        # Programmer error
        assert False

  def _read_tree(self, rev, path):
    """Given a revision and path, parse the tree data out of git cat-file output.

    :returns: a dict from filename -> [list of Symlink, Dir, and Fil objectse]
    """

    tree = self._trees[rev].get(path)
    if tree:
      return tree
    tree = {}
    object_type, tree_data = self._read_object_from_repo(rev=rev, relpath=path)
    assert object_type == 'tree'
    # The tree data here is (mode ' ' filename \0 20-byte-sha)*
    i = 0
    while i < len(tree_data):
      start = i
      while tree_data[i] != ' ':
        i += 1
      mode = tree_data[start:i]
      i += 1 # skip space
      start = i
      while tree_data[i] != '\0':
        i += 1
      name = tree_data[start:i]
      sha = tree_data[i+1:i+21].encode('hex')
      i += 21
      if mode == '120000':
        tree[name] = self.Symlink(name, sha)
      elif mode == '40000':
        tree[name] = self.Dir(name, sha)
      else:
        tree[name] = self.File(name, sha)
    self._trees[rev][path] = tree
    return tree

  def _read_object_from_repo(self, rev=None, relpath=None, sha=None):
    """Read an object from the git repo.
    This is implemented via a pipe to git cat-file --batch
    """
    if sha:
      spec = sha + '\n'
    else:
      assert rev is not None
      assert relpath is not None
      spec = '{}:{}\n'.format(rev, relpath)
    self._cat_file_process.stdin.write(spec)
    self._cat_file_process.stdin.flush()
    header = None
    while not header:
      header = self._cat_file_process.stdout.readline()
      if self._cat_file_process.poll():
        import pdb;pdb.set_trace()
        raise self.GitDiedException("Git cat-file died while trying to read '{}'.".format(spec))
    header = header.rstrip()
    parts = header.rsplit(ensure_binary(' '), 2)
    if len(parts) == 2:
      assert parts[1] == 'missing'
      raise self.MissingFileException(rev, relpath)

    _, object_type, object_len = parts

    # Read the object data
    blob = self._cat_file_process.stdout.read(int(object_len))

    # Read the trailing newline
    assert self._cat_file_process.stdout.read(1) == '\n'
    assert len(blob) == int(object_len)
    return object_type, blob
