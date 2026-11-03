package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkOutcomesTest {
    @Test
    fun closeCodesWithTheirOwnPages() {
        assertEquals(LinkOutcome.Revoked, LinkOutcomes.ofClose(4401, "revoked"))
        assertEquals(
            LinkOutcome.Show(ConnectionIssue.HermesNotRunning("close 4503 hermes_not_running")),
            LinkOutcomes.ofClose(4503, "hermes_not_running"),
        )
        assertEquals(
            LinkOutcome.Show(ConnectionIssue.HermesUpdated("close 4505 hermes_protocol_unsupported (unsupported hermes runtime protocol: 2)")),
            LinkOutcomes.ofClose(4505, "hermes_protocol_unsupported (unsupported hermes runtime protocol: 2)"),
        )
    }

    @Test
    fun everyOtherCloseIsTheGenericRetryPage() {
        listOf(1000 to "", 1001 to "going_away", 1011 to "pong_timeout", 1013 to "try_again", 4429 to "rate_limited",
            4430 to "too_many_connections", 4502 to "hermes_unavailable").forEach { (code, reason) ->
            val outcome = LinkOutcomes.ofClose(code, reason)
            assertEquals(LinkOutcome.Show(ConnectionIssue.Generic(LinkOutcomes.closeDetails(code, reason))), outcome)
        }
        assertEquals("close 1000", LinkOutcomes.closeDetails(1000, ""))
    }

    @Test
    fun failuresWithoutACloseFrame() {
        assertEquals(LinkOutcome.Revoked, LinkOutcomes.ofFailure(401, ConnectionProblem.Unreachable))
        assertEquals(
            LinkOutcome.Show(ConnectionIssue.Generic("/v1/ws HTTP 403")),
            LinkOutcomes.ofFailure(403, ConnectionProblem.Unreachable),
        )
        assertEquals(
            ConnectionIssue.CertificateChanged::class,
            (LinkOutcomes.ofFailure(null, ConnectionProblem.CertificateChanged) as LinkOutcome.Show).issue::class,
        )
        assertEquals(
            LinkOutcome.Show(ConnectionIssue.Generic("no connection")),
            LinkOutcomes.ofFailure(null, ConnectionProblem.Unreachable),
        )
        assertEquals(
            ConnectionIssue.TailscaleOff::class,
            (LinkOutcomes.ofFailure(null, ConnectionProblem.TailscaleOff) as LinkOutcome.Show).issue::class,
        )
    }

    @Test
    fun meDecidesWhetherToOpenTheSocket() {
        assertNull(LinkOutcomes.ofMe(MeResult.Ok("dev_1", "poco")))
        assertEquals(LinkOutcome.Revoked, LinkOutcomes.ofMe(MeResult.Unauthorized))
        assertEquals(
            ConnectionIssue.CertificateChanged::class,
            (LinkOutcomes.ofMe(MeResult.Failed(ConnectionProblem.CertificateChanged)) as LinkOutcome.Show).issue::class,
        )
        assertEquals(LinkOutcome.Show(ConnectionIssue.Generic("/v1/me HTTP 403")), LinkOutcomes.ofMe(MeResult.Forbidden))
    }
}
