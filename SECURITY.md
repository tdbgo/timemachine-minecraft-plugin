# TimeMachine security policy

TimeMachine handles backup data, filesystem paths, and a permission-controlled command surface. Reports involving path traversal, unintended file access or deletion, permission bypass, corrupted backup publication, or denial of service should be treated as security issues.

## Reporting

Use GitHub's private vulnerability reporting for this repository when it is available. If it is not enabled, contact the maintainer through the repository owner's GitHub profile before sharing exploit details. Do not include live world data, database files, server addresses, credentials, or other secrets in a report.

Include the affected plugin version, Paper and Java versions, relevant configuration with secrets removed, reproduction steps, and the expected safety boundary.

## Supported versions

Before `1.0.0`, security fixes are provided for the latest published release only. Operators should update to the newest release and keep an independently tested off-site backup.
