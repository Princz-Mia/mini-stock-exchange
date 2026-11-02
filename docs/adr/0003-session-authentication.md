# ADR 0003: Server-side sessions for people

Status: accepted. Live-stream authentication and bot credentials will extend this ADR when they are built.

## Context

The browser needs authenticated access to trading on the same origin as the UI. Credentials must not be readable by page scripts, and logout must take effect immediately.

## Decision

Human users authenticate with server-side sessions stored in `execution_db` through Spring Session JDBC, using an `HttpOnly`, `SameSite=Lax` cookie that is `Secure` over HTTPS. All cookie-authenticated mutations, including login and logout, need a CSRF token bound to the session. The session ID and CSRF token rotate on login. Passwords are stored with Spring's adaptive delegating encoder. Login takes JSON through a controller, not form login.

Demo users are created only from configured passwords, so no default login exists.

## Consequences

- Logout and revocation are immediate, and sessions survive a service restart.
- Each session costs database writes on the same server as trading. Session traffic must be watched in the load test.
- The client makes an extra call to get a CSRF token, and another after login.
- Bots need a separate stateless credential, and that chain must not let a session cookie bypass CSRF.

## Revisit when

Clients on other origins or a third-party identity provider become a requirement, or the load test shows session writes affect trading latency.
