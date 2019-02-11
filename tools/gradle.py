#!/usr/bin/env python
# Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

# Wrapper script for running gradle.
# Will make sure we pulled down gradle before running, and will use the pulled
# down version to have a consistent developer experience.

import optparse
import os
import subprocess
import sys

import jdk
import utils

GRADLE_DIR = os.path.join(utils.REPO_ROOT, 'third_party', 'gradle')
GRADLE_SHA1 = os.path.join(GRADLE_DIR, 'gradle.tar.gz.sha1')
GRADLE_TGZ = os.path.join(GRADLE_DIR, 'gradle.tar.gz')

if utils.IsWindows():
  GRADLE = os.path.join(GRADLE_DIR, 'gradle', 'bin', 'gradle.bat')
else:
  GRADLE = os.path.join(GRADLE_DIR, 'gradle', 'bin', 'gradle')

def ParseOptions():
  result = optparse.OptionParser()
  result.add_option('--java-home', '--java_home',
      help='Use a custom java version to run gradle.')
  return result.parse_args()

def GetJavaEnv(env):
  return dict(env if env else os.environ, JAVA_HOME = jdk.GetJdkHome())

def PrintCmd(s):
  if type(s) is list:
    s = ' '.join(s)
  print 'Running: %s' % s
  # I know this will hit os on windows eventually if we don't do this.
  sys.stdout.flush()

def EnsureGradle():
  utils.EnsureDepFromGoogleCloudStorage(
    GRADLE, GRADLE_TGZ, GRADLE_SHA1, 'Gradle binary')

def EnsureDeps():
  EnsureGradle()
  jdk.EnsureJdk()

def RunGradleIn(gradleCmd, args, cwd, throw_on_failure=True, env=None):
  EnsureDeps()
  cmd = [gradleCmd]
  cmd.extend(args)
  utils.PrintCmd(cmd)
  with utils.ChangedWorkingDirectory(cwd):
    return_value = subprocess.call(cmd, env=GetJavaEnv(env))
    if throw_on_failure and return_value != 0:
      raise Exception('Failed to execute gradle')
    return return_value

def RunGradleWrapperIn(args, cwd, throw_on_failure=True, env=None):
  return RunGradleIn('./gradlew', args, cwd, throw_on_failure, env=env)

def RunGradle(args, throw_on_failure=True, env=None):
  return RunGradleIn(GRADLE, args, utils.REPO_ROOT, throw_on_failure, env=env)

def RunGradleExcludeDeps(args, throw_on_failure=True, env=None):
  EnsureDeps()
  args.append('-Pexclude_deps')
  return RunGradle(args, throw_on_failure, env=env)

def RunGradleInGetOutput(gradleCmd, args, cwd, env=None):
  EnsureDeps()
  cmd = [gradleCmd]
  cmd.extend(args)
  utils.PrintCmd(cmd)
  with utils.ChangedWorkingDirectory(cwd):
    return subprocess.check_output(cmd, env=GetJavaEnv(env))

def RunGradleWrapperInGetOutput(args, cwd, env=None):
  return RunGradleInGetOutput('./gradlew', args, cwd, env=env)

def RunGradleGetOutput(args, env=None):
  return RunGradleInGetOutput(GRADLE, args, utils.REPO_ROOT, env=env)

def Main():
  (options, args) = ParseOptions()
  gradle_args = sys.argv[1:]
  if options.java_home:
    gradle_args.append('-Dorg.gradle.java.home=' + options.java_home)
  return RunGradle(gradle_args)

if __name__ == '__main__':
  sys.exit(Main())
