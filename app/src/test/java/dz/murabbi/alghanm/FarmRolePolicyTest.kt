package dz.murabbi.alghanm

import org.junit.Assert.*
import org.junit.Test

class FarmRolePolicyTest {
    @Test fun administratorHasAllCapabilities() {
        assertTrue(FarmRolePolicy.canManageUsers("admin"))
        assertTrue(FarmRolePolicy.canManageBackup("admin"))
        assertTrue(FarmRolePolicy.canViewAudit("admin"))
        assertTrue(FarmRolePolicy.canManageFinance("admin"))
        assertTrue(FarmRolePolicy.canManageMedicineStock("admin"))
    }
    @Test fun veterinarianCannotManageFinanceOrUsers() {
        assertTrue(FarmRolePolicy.canManageAnimals("veterinarian"))
        assertTrue(FarmRolePolicy.canManageTreatments("veterinarian"))
        assertTrue(FarmRolePolicy.canManageVaccines("veterinarian"))
        assertFalse(FarmRolePolicy.canManageFinance("veterinarian"))
        assertFalse(FarmRolePolicy.canManageUsers("veterinarian"))
    }
    @Test fun stockkeeperIsLimitedToStockOperations() {
        assertTrue(FarmRolePolicy.canManageMedicineStock("stockkeeper"))
        assertFalse(FarmRolePolicy.canManageFinance("stockkeeper"))
        assertFalse(FarmRolePolicy.canManageTreatments("stockkeeper"))
        assertFalse(FarmRolePolicy.canViewAudit("stockkeeper"))
    }
    @Test fun financeRoleCannotChangeClinicalOrStockRecords() {
        assertTrue(FarmRolePolicy.canManageFinance("finance"))
        assertFalse(FarmRolePolicy.canManageTreatments("finance"))
        assertFalse(FarmRolePolicy.canManageMedicineStock("finance"))
    }
    @Test fun viewerAndUnknownRolesFailClosed() {
        assertTrue(FarmRolePolicy.canViewFarm("viewer"))
        assertFalse(FarmRolePolicy.canManageFinance("viewer"))
        assertFalse(FarmRolePolicy.canManageBackup("unknown"))
        assertFalse(FarmRolePolicy.canViewFarm("unknown"))
    }
}
