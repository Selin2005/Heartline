# Security policy

## Supported versions

Only the latest **stable** release and the latest **beta** release get security fixes. Please
update before you report a problem.

## Reporting a vulnerability

**Please don't open a public issue for security problems.** Report them privately through
[GitHub private vulnerability reporting](https://github.com/selin2005/heartline/security/advisories/new).

Include:
- the affected version (Settings → About on the phone) and devices;
- steps to reproduce, and what an attacker could achieve;
- a proof of concept, if you have one.

We aim to acknowledge reports within 7 days and to ship a fix, or explain our assessment, within
30 days. We'll credit you in the release notes unless you prefer otherwise.

## Scope

Examples of issues that are in scope:
- health data leaving the device without the user's action;
- another app on the phone or watch reading Heartline's data or triggering its components;
- the in-app updater installing a file that isn't an official, checksum-verified release.

Measurement accuracy is not a security issue. Please report it as a normal bug, and remember that
Heartline is not a medical device.
