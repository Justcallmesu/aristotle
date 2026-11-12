# Release — run decisions
- 2026-11-06 User asked for an installer after signing + licences ("can we make the installer?") and answered Q126-Q129. PM reading: that is the go for option A (T22 signing + licences) plus the step-1 bundle; RS2 and RS3 start now, RS1 waits for the user-created signing key (RS0).
- 2026-11-09 Q132-Q134 answered via clarify (all A).
- 2026-11-12 Complete installer: Q135 local only (PM recommended both), Q136 A, Q137 A, Q138 A via clarify. IN1/IN2/IN3 dispatched in parallel against the P22 contract (disjoint files); IN4 (docs + 2.2.0 release + re-install on cachyos-asus-1) after.
- 2026-11-12 Q139 = A (keep as built: default home always made reachable, extra gateway for a non-default HERMES_HOME setup accepted).
- 2026-11-12 Re-review: #1-#7 CLOSED; new MAJOR gateway wait 30 s too short + MINOR silent downgrade in root installer. IN2 fix rounds used up -> user chose 'Fix now' (P26): IN2c, PM gates + mutation, no 3rd review; 2.2.0 real-PC install is the check. IN1 committed (reviewed, gated, mutant killed).
