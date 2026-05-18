# java-sftp-pass-wrapper

Java-based SFTP wrapper for environments where `sshpass` is unavailable or disallowed.

This project does **not** try to feed a password into OpenSSH `sftp`. Instead, it performs SFTP directly from Java using JSch, which avoids pseudo-terminal tricks and works in restricted environments.

## Features

- Password authentication via environment variable, stdin, password file, or interactive prompt
- Basic SFTP commands: `put`, `get`, `ls`, `rm`, `mkdir`, `rmdir`, `rename`
- Batch mode for a practical subset of OpenSSH `sftp -b` scripts
- Strict host key checking by default via `~/.ssh/known_hosts`
- Optional `--insecure` mode for legacy/internal environments

## Build

This project targets Java 8 / 1.8.

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
The workflow executes `./gradlew clean test fatJar` with Java 8 and uploads
`build/libs/*-all.jar` as a workflow artifact named with the tag. Tag
characters that are not safe in artifact names are replaced with `-`.

## Examples

### Upload a file

```bash
export SFTP_PASSWORD='secret'
java -jar build/libs/java-sftp-pass-wrapper-0.1.0-SNAPSHOT-all.jar \
  --host sftp.example.com --user deploy \
  put ./local.txt /upload/local.txt
```

### Download a file with password from stdin

```bash
printf '%s' "$SFTP_PASSWORD" | java -jar build/libs/java-sftp-pass-wrapper-0.1.0-SNAPSHOT-all.jar \
  --host sftp.example.com --user deploy --password-stdin \
  get /upload/report.csv ./report.csv
```

### Run batch commands

`batch.sftp`:

```text
cd /upload
put ./report.csv report.csv
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
