# Code signing policy — Exam Question Bank

**Status:** Proposed policy; trusted code signing is **not yet operational**. SignPath Foundation has not approved this project, and the Windows release workflow has not yet been integrated with SignPath.

**Project:** [Exam Question Bank](https://github.com/corneym/Exam)  
**Current signing work:** [Issue #217](https://github.com/corneym/Exam/issues/217)  
**Official downloads:** [GitHub Releases](https://github.com/corneym/Exam/releases)

## Signing provider and current release status

**Intended provider, subject to acceptance:** Free code signing provided by SignPath.io, certificate by SignPath Foundation.

If this arrangement is approved, Windows will identify **SignPath Foundation** as the certificate publisher for SignPath-signed files; the certificate will not be issued in the individual maintainer's name. No statement on this page means that approval or signing has already occurred.

The published **v0.7 MSI is unsigned**. Earlier published installer files will not be silently replaced or described as signed. Users should obtain installers from official GitHub Releases, heed Windows security warnings, and comply with any organisation-managed installation policy. A valid digital signature proves the signing identity and file integrity; it does not guarantee that Microsoft Defender SmartScreen or other security products will never display a warning.

## Project ownership and signing roles

The original application is independently maintained in the public [corneym/Exam](https://github.com/corneym/Exam) repository. The following responsibilities apply to this project:

| Role | Assigned member and responsibility |
| --- | --- |
| Author / committer | [@corneym](https://github.com/corneym) — maintains application source, build scripts and release workflows. |
| Reviewer | [@corneym](https://github.com/corneym) — reviews contributions from people who are not authorised committers before merge, with particular attention to build and signing changes. |
| Release-signing approver | [@corneym](https://github.com/corneym) — inspects the source revision, automated build origin, release contents and verification evidence before manually approving each signing request. |

These roles may be revised publicly if maintainership changes. Source-repository and signing-service access for all signing team members **must use multi-factor authentication (MFA)**. Signing permissions are restricted to the designated maintainer and required trusted automation; MFA and service access will be verified before signing is enabled.

Signing approval must never be delegated to an unattended script or treated as implicit merely because continuous integration (CI) passed. Each release requires a deliberate, recorded human approval in the signing service.

## What may be signed

Only Exam Question Bank binaries built from the project's public, reviewed source and build configuration may use this signing arrangement:

- The project's Windows application launcher, `Exam Question Bank.exe`, created by `jpackage`.
- The project's versioned Windows MSI installer containing the application image and its runtime dependencies.
- Other project-produced executables only if they are explicitly inventoried, approved and added to the verified signing policy.

Third-party executables and libraries are **not** individually signed as if they were produced by Exam Question Bank. Upstream dependencies may be included in the packaged application subject to their own licences and the signing provider's conditions. The project must review upstream components and licence obligations before activating free OSS signing.

Examination papers, answers, curriculum source documents, user-generated question-bank records and databases are **not** part of the distributed installer or signing scope. The repository's root `data/` directory is excluded from Git tracking. The MIT licence applies to the project's original licensed code, not to separately owned third-party material.

## Trusted build, version metadata and approval

This is the required process **once signing is implemented**; it does not describe the current unsigned release process as already compliant.

1. Release changes, including source code, dependencies, Windows packaging scripts and CI workflows, are committed to the public repository and pass the relevant checks. External contributors' changes are reviewed before merge. Changes affecting build provenance or the signing process receive explicit maintainer scrutiny.
2. A dedicated, trusted **Windows GitHub Actions release workflow** builds the release candidate from an identifiable protected-branch source revision and release tag. The trusted CI system supplies verifiable source and build provenance. Locally built or manually substituted executables must not enter the SignPath Foundation signing pipeline.
3. Release candidates retain consistent product metadata: **product name `Exam Question Bank`** and a single matching release version for the launcher and MSI. SignPath file-metadata restrictions must enforce the applicable product-name and product-version attributes; mismatches block signing.
4. The newly built launcher is signed and verified before the MSI is constructed from that same signed application image. The pipeline must **not rebuild or replace the signed image** between those steps. The resulting MSI is then signed and independently verified. Signing arrangements must respect SignPath's origin-verification and approval requirements for each signing request.
5. The designated approver reviews the source revision, source/build provenance, CI results, contents, version and pending signing request, and grants **manual approval** for the release. Rejected requests are not published as trusted releases.
6. Verification must establish valid Authenticode signatures, trusted certificate chains, expected publisher information and appropriate trusted timestamps. The release process fails rather than publishing an unsigned, invalidly signed or unexpectedly changed production installer.
7. The **exact signed MSI** is archived immutably and attached to the matching GitHub Release with its SHA-256 checksum, source tag/commit and public verification guidance. It must not be modified or silently replaced after signing.
8. Before publication, installation, startup, upgrade, uninstall and preservation of separately stored user data must be checked on a Windows machine other than the build system.

The existing CI tests currently run on Ubuntu, and the normal MSI build is local. The trusted Windows build and SignPath integration are therefore prerequisites, not completed controls. An implementation plan and its verification results are tracked in [Issue #217](https://github.com/corneym/Exam/issues/217).

## Privacy and data handling

**This program will not transfer any information to other networked systems unless specifically requested by the user or the person installing or operating it.**

Exam Question Bank stores its SQLite question bank, source assets and settings locally in a configurable user-owned data directory. The default Windows configuration and data paths are under `%LOCALAPPDATA%\Exam Question Bank Data\`. User-initiated backup, restore, export and import operations use files and destinations chosen or configured by the operator.

Application installation does not upload examination content, question-bank records or local curriculum documents. The installer does not bundle a user database, examination papers or curriculum source datasets. The application's bundled runtime libraries are third-party components; their own licence terms continue to apply. If a future feature adds network services or telemetry, its data-transfer behaviour and any applicable third-party privacy requirements must be documented and reviewed before release.

## System changes and uninstallation

The installer performs a **per-user Windows MSI installation** and may create a Start Menu entry. Writable settings and question-bank data are held separately from installed program files. The application must not make undisclosed security-sensitive system configuration changes.

Users can uninstall Exam Question Bank using **Windows Settings → Apps → Installed apps** or the normal Windows MSI uninstallation facilities. Uninstallation is not intended to remove the independently stored question-bank database or user-managed source documents; users should retain their own backups.

## Verification, reporting and security

Once signed releases become available, a recipient can check the installed launcher and downloaded MSI using Windows Authenticode verification tools, verify the published SHA-256 checksum and compare the release with its official GitHub tag. Verification instructions must not require disabling Windows Defender, SmartScreen or organisation security controls.

Signing credentials, private keys and service tokens must never be included in the source repository, CI logs, release artefacts or user installation files. Managed signing credentials must use the signing provider's protected custody and CI secret management with least-privilege access.

If a release is suspected of tampering, malicious behaviour, compromised credentials or policy violations, the maintainer will investigate, stop publication/signing where appropriate, cooperate with the signing provider and request revocation if warranted. Please report concerns through the repository's [Issue tracker](https://github.com/corneym/Exam/issues) without disclosing private credentials or sensitive user data publicly.

## Policy references

- [SignPath Foundation conditions for open-source projects](https://signpath.org/terms.html)
- [SignPath documentation — signing policies and trusted builds](https://docs.signpath.io/projects)
- [Issue #217 — implementation and verification](https://github.com/corneym/Exam/issues/217)

**Publication requirement:** Link this page using the label **Code signing policy** from the repository home page and from the release notes of each future release. The link must remain present whether a particular release is signed or unsigned, with its status accurately described.
