# Security policy

## Reporting a vulnerability
Please **do not open a public issue**. Report privately through
[GitHub private vulnerability reporting](https://github.com/manjunathyadavs1998/growercare/security/advisories/new),
or contact the maintainer directly.

Include the affected component and version (commit), steps to reproduce, the impact, and the `traceId` from any error response.
Never include real personal data or live tokens.

**Response targets:**
- acknowledgement within 3 working days;
- assessment within 10;
- fix or mitigation timeline agreed with the reporter.

## Scope and known dev-only material
- `.env.example` here and the growercare-local-platform repository hold **local development credentials only**:
  - the Keycloak realm test users;
  - the `case-service` client secret;
  - `.env.example` values.

  They aren't secrets and must never be reused outside a developer machine.
- Production secrets live in AWS Secrets Manager (see `docs/architecture/07_DEPLOYMENT_AWS.md`) and are never committed.

## Practices in this repository
- **Authentication and authorization:** OAuth2/JWT validation, grant-based RBAC with geo/BU scope, out-of-scope = 404 (SPEC-CASE-01, skill `java-security-rbac`).
- **Database access:** least-privilege roles (DML-only runtime role); append-only audit; no ORM; parameterised SQL only.
- **Dependencies:** Dependabot alerts and grouped weekly updates. GitHub Actions pinned to commit SHAs.
- **Static analysis:** CodeQL and dependency review run when the repository is public (SPEC-PLAT-02 D4).
