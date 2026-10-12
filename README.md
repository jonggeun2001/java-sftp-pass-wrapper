# java-sftp-pass-wrapper

Java-based SFTP and SSH command wrapper for environments where `sshpass` is unavailable or disallowed.

This project does **not** try to feed a password into OpenSSH `sftp`. Instead, it performs SFTP and SSH command execution directly from Java using JSch, which avoids pseudo-terminal tricks and works in restricted environments.

## Features

- Password authentication via environment variable, stdin, password file, or interactive prompt
- Custom SSH/SFTP port via `-P` / `--port` (defaults to `22`)
- Basic SFTP commands: `put`, `get`, `ls`, `rm`, `mkdir`, `rmdir`, `rename`
- Multiple-file transfers with `mget` / `mput` and basename patterns (`*`, `?`)
- Recursive directory downloads/uploads with `get -r`, `put -r`, `mget -r`, `mput -r`
- Optional post-upload chmod via `put --chmod <mode>` / `mput --chmod <mode>`
- Batch mode for a practical subset of OpenSSH `sftp -b` scripts
- Remote command execution with literal arguments via `exec`, streamed stdout/stderr and the remote exit code
- Strict host key checking by default via `~/.ssh/known_hosts`
- Optional `--insecure` mode for legacy/internal environments

## Build

This project targets Java 8 / 1.8.
The worktree verification script checks for common Java 9+ syntax/API usage
before running the Gradle test suite. PRs and pushes to `main` also run
`scripts/verify-worktree.sh` on Java 8 in CI. Transfer integration tests start
an isolated SFTP server on loopback with an ephemeral port and temporary files;
no external server or credentials are needed. Windows CI additionally verifies
pattern transfers on Java 8. Exec integration tests use an isolated SSH server
and `/bin/sh` on Linux/macOS to verify argument preservation, output, exit codes,
and execution without an SFTP subsystem. Optional Gradle test filters can be passed to
`scripts/verify-worktree.sh --tests '*TransferIntegrationTest'`.

```bash
./gradlew clean test fatJar
```

Runnable jar:

```bash
java -jar build/libs/java-sftp-pass-wrapper-0.1.0-SNAPSHOT-all.jar --help
```

Or use Gradle's application distribution:

```bash
./gradlew installDist
./build/install/sftp-pass/bin/sftp-pass --help
```

## Tagged builds

Pushing any Git tag runs the `Build fat JAR on tag` GitHub Actions workflow.
The workflow executes `./gradlew clean test fatJar` with Java 8, uploads
`build/libs/*-all.jar` as a workflow artifact named with the tag, and attaches
the same jar to the matching GitHub Release. If the GitHub Release does not
exist yet, the workflow creates it from the tag first. Tag characters that are
not safe in artifact names are replaced with `-`.

## Release preparation

The release tag flow uses `.agents/release-tag-manager/version-bump.sh` to
update the Gradle project version before promoting `main` to `release`. The
hook is intentionally limited to `build.gradle.kts`, as listed in
`.agents/release-tag-manager/version-bump-allowlist.txt`.

Before running release tagging for the first time, create `origin/release` from
the reviewed `main` commit that should become the initial release baseline.

## Examples

### Upload a file

```bash
export SFTP_PASSWORD='secret'
java -jar build/libs/java-sftp-pass-wrapper-0.1.0-SNAPSHOT-all.jar \
  --host sftp.example.com --user deploy \
  put ./local.txt /upload/local.txt
```

### Upload a file and set permissions

`--chmod` accepts a 3- or 4-digit octal mode such as `777`, `0755`, or `1777`.
The mode is applied to the remote file after the upload succeeds.

```bash
export SFTP_PASSWORD='secret'
java -jar build/libs/java-sftp-pass-wrapper-0.1.0-SNAPSHOT-all.jar \
  --host sftp.example.com --user deploy \
  put --chmod 777 ./script.sh /upload/script.sh
```

### Download a file with password from stdin

```bash
printf '%s' "$SFTP_PASSWORD" | java -jar build/libs/java-sftp-pass-wrapper-0.1.0-SNAPSHOT-all.jar \
  --host sftp.example.com --user deploy --password-stdin \
  get /upload/report.csv ./report.csv
```

### Download or upload a directory recursively

`mget` / `mput` mean multiple-file transfer. Recursion is enabled separately
with `-r` (aliases: `-R`, `--recursive`) on all four transfer commands.

```bash
# Include the directory's files, subdirectories, hidden files and empty directories.
sftp-pass --host sftp.example.com --user deploy get -r /reports ./reports
sftp-pass --host sftp.example.com --user deploy put -r ./data /upload/data
```

For `get -r` / `put -r`, an existing destination directory receives a child
with the source basename. A destination that does not exist becomes the copied
root itself. For example, `put -r ./data /upload` creates `/upload/data` when
`/upload` exists; `put -r ./data /renamed` creates `/renamed` when it does not.
Use a named source directory when copying into an existing directory; a remote
filesystem root has no basename. Upload parent directories must already exist.

### Transfer multiple matching files or directories

```bash
mkdir -p ./downloads
sftp-pass --host sftp.example.com --user deploy mget '/reports/*.csv' ./downloads
sftp-pass --host sftp.example.com --user deploy mput './reports/*.csv' /upload
sftp-pass --host sftp.example.com --user deploy mget -r '/reports/2026-*' ./downloads
sftp-pass --host sftp.example.com --user deploy mput -r --chmod 640 './data*' /upload
```

Syntax: `mget [-r] <remote-pattern> [local-directory]` and
`mput [-r] [--chmod MODE] <local-pattern> [remote-directory]`.
Each command accepts one source path/pattern; quote patterns to prevent shell
expansion. `*` and `?` are supported in the final path component only, including
matches starting with a dot. Bracket expressions, braces and `**` recursive-glob
semantics are not supported. Use `-r` to descend into matched directories.
The target must be an existing directory and defaults to the current local or
remote directory. Each match retains its basename. No matches is an error;
a matched directory without `-r` is also an error.

Recursive and multi-file transfers reject symbolic links and special files,
including links in source/destination ancestors, rather than following them.
This check also applies before resolving `..` components.
Selected download entry names containing path separators or a colon are rejected for
portable, safe local paths. Existing regular files are overwritten. Transfers
stop on the first failure and are not atomic: files/directories already copied
and a partially written file can remain. `--chmod` applies after each successful
file upload, including nested files; directory permissions are left at server
defaults. Ownership, timestamps and source permissions are not preserved.

### Use a custom port

```bash
export SFTP_PASSWORD='secret'
java -jar build/libs/java-sftp-pass-wrapper-0.1.0-SNAPSHOT-all.jar \
  --host sftp.example.com --user deploy --port 2222 \
  ls /upload
```

### Execute a remote command with arguments

```bash
export SFTP_PASSWORD='secret'
sftp-pass --host ssh.example.com --user deploy exec sh /upload/script.sh arg1 "two words"
sftp-pass --host ssh.example.com --user deploy exec ls -la /upload
```

Syntax: `exec [connection-options] [--] <command> [args...]`.
Connection and password options work before or after `exec`, but must precede
the remote command. Everything after the command is a literal remote argument,
including `--host`, `--help`, `--`, and `@filename`. Use `exec --help` for the
wrapper's help. A command beginning with `-` needs the explicit `--` delimiter.

Each command/argument is quoted for a POSIX-compatible remote login shell:
spaces, empty arguments, quotes, wildcards, and shell metacharacters are preserved.
For pipelines, redirection, or other shell syntax, explicitly run `sh -c`:

```bash
sftp-pass --host ssh.example.com --user deploy exec sh -c 'cd /upload && ./script.sh "$1"' sh "two words"
```

Remote stdout/stderr stream to local stdout/stderr, and the wrapper returns the
remote exit code. Connection/SSH errors or a missing exit status return a nonzero
wrapper error. This is noninteractive execution: no PTY is allocated and remote
stdin receives EOF. `--timeout` controls connection/channel setup, not the command's
runtime. The server must permit SSH exec requests; an SFTP-only account cannot run
commands. `exec` is a standalone subcommand and is not available inside SFTP batch
files. Windows remote shells such as `cmd.exe`/PowerShell are not supported by
this POSIX quoting mode.

### Run batch commands

`batch.sftp`:

```text
cd /upload
put ./report.csv report.csv
mput "./exports/*.csv" .
put -r ./assets assets
get -r assets ./assets-copy
ls .
bye
```

Run:

```bash
export SFTP_PASSWORD='secret'
java -jar build/libs/java-sftp-pass-wrapper-0.1.0-SNAPSHOT-all.jar \
  --host sftp.example.com --user deploy \
  batch batch.sftp
```

Batch transfers use the same `-r` / `-R` / `--recursive` options and upload
`--chmod MODE` support. `cd` / `lcd` affect relative paths; omitted targets
default to the corresponding current directory. Prefix a command with `-` to
ignore its failure and continue. Use `--` before paths beginning with `-`.

## Password source priority

Only one non-default source may be selected:

1. `--password-stdin`
2. `--password-file <path>`
3. `--password-env <ENV_NAME>` (defaults to `SFTP_PASSWORD`)
4. Interactive prompt if no password is found

Avoid passing passwords as command-line arguments because they can be exposed through process listings and shell history.

## Host key security

By default, the wrapper uses strict host key checking and reads `~/.ssh/known_hosts`.

For first-time internal testing only:

```bash
... --insecure ls /
```

Prefer adding the server host key to `known_hosts` instead of using `--insecure` permanently.
