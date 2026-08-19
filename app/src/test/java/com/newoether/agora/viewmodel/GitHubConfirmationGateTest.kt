package com.newoether.agora.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubConfirmationGateTest {
    @Test
    fun `approved mutation may continue`() {
        assertTrue(requireGitHubMutationApproved(true))
    }

    @Test(expected = GitHubMutationDeniedException::class)
    fun `denied mutation throws before remote write`() {
        requireGitHubMutationApproved(false)
    }
}
