package dz.murabbi.alghanm

/** Explicit role capabilities. Unknown roles fail closed. */
internal object FarmRolePolicy {
    const val ADMIN = "admin"
    const val VETERINARIAN = "veterinarian"
    const val STOCKKEEPER = "stockkeeper"
    const val FINANCE = "finance"
    const val VIEWER = "viewer"

    fun canManageUsers(role: String) = role == ADMIN
    fun canManageBackup(role: String) = role == ADMIN
    fun canViewAudit(role: String) = role == ADMIN
    fun canManageAnimals(role: String) = role in setOf(ADMIN, VETERINARIAN)
    fun canManageTreatments(role: String) = role in setOf(ADMIN, VETERINARIAN)
    fun canManageVaccines(role: String) = role in setOf(ADMIN, VETERINARIAN)
    fun canManageMedicineStock(role: String) = role in setOf(ADMIN, VETERINARIAN, STOCKKEEPER)
    fun canManageFinance(role: String) = role in setOf(ADMIN, FINANCE)
    fun canViewFarm(role: String) = role in setOf(ADMIN, VETERINARIAN, STOCKKEEPER, FINANCE, VIEWER)
}
