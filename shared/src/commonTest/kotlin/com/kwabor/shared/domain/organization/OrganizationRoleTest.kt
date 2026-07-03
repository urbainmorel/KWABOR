package com.kwabor.shared.domain.organization

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganizationRoleTest {
    @Test
    fun includes_keepsRolesCumulative() {
        assertTrue(OrganizationRole.Owner.includes(OrganizationRole.Moderator))
        assertTrue(OrganizationRole.Owner.includes(OrganizationRole.Editor))
        assertTrue(OrganizationRole.Owner.includes(OrganizationRole.Manager))
        assertTrue(OrganizationRole.Manager.includes(OrganizationRole.Editor))
        assertTrue(OrganizationRole.Editor.includes(OrganizationRole.Moderator))

        assertFalse(OrganizationRole.Moderator.includes(OrganizationRole.Editor))
        assertFalse(OrganizationRole.Editor.includes(OrganizationRole.Manager))
        assertFalse(OrganizationRole.Manager.includes(OrganizationRole.Owner))
    }

    @Test
    fun canAssign_respectsTeamManagementBoundary() {
        assertTrue(OrganizationRole.Owner.canAssign(OrganizationRole.Manager))
        assertTrue(OrganizationRole.Owner.canAssign(OrganizationRole.Editor))
        assertTrue(OrganizationRole.Owner.canAssign(OrganizationRole.Moderator))
        assertTrue(OrganizationRole.Manager.canAssign(OrganizationRole.Editor))
        assertTrue(OrganizationRole.Manager.canAssign(OrganizationRole.Moderator))

        assertFalse(OrganizationRole.Owner.canAssign(OrganizationRole.Owner))
        assertFalse(OrganizationRole.Manager.canAssign(OrganizationRole.Manager))
        assertFalse(OrganizationRole.Editor.canAssign(OrganizationRole.Moderator))
        assertFalse(OrganizationRole.Moderator.canAssign(OrganizationRole.Editor))
    }

    @Test
    fun canAllocateBudgetTo_excludesModeratorAndOwnerTargets() {
        assertTrue(OrganizationRole.Owner.canAllocateBudgetTo(OrganizationRole.Manager))
        assertTrue(OrganizationRole.Owner.canAllocateBudgetTo(OrganizationRole.Editor))
        assertTrue(OrganizationRole.Manager.canAllocateBudgetTo(OrganizationRole.Editor))

        assertFalse(OrganizationRole.Owner.canAllocateBudgetTo(OrganizationRole.Moderator))
        assertFalse(OrganizationRole.Owner.canAllocateBudgetTo(OrganizationRole.Owner))
        assertFalse(OrganizationRole.Manager.canAllocateBudgetTo(OrganizationRole.Manager))
        assertFalse(OrganizationRole.Manager.canAllocateBudgetTo(OrganizationRole.Moderator))
        assertFalse(OrganizationRole.Editor.canAllocateBudgetTo(OrganizationRole.Editor))
    }
}
