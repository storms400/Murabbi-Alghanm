package dz.murabbi.alghanm

import dz.murabbi.alghanm.data.MedicineStock
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MedicineStockAlertPolicyTest {
    private val today = LocalDate.of(2026, 11, 7)

    @Test fun expiredBatchAlwaysAlerts() {
        val item = MedicineStock(medicineName = "دواء أ", quantity = 8.0, minimumQuantity = 2.0, expiryDate = "2026-11-06")
        assertTrue(MedicineStockAlertPolicy.alerts(listOf(item), today).single().contains("منتهي الصلاحية"))
    }

    @Test fun lowStockUsesConfiguredThreshold() {
        val item = MedicineStock(medicineName = "دواء ب", quantity = 2.0, minimumQuantity = 2.0, expiryDate = "2027-01-01")
        assertTrue(MedicineStockAlertPolicy.alerts(listOf(item), today).single().contains("المخزون منخفض"))
    }

    @Test fun expiryWithinThirtyDaysAlertsButDayThirtyOneDoesNot() {
        val soon = MedicineStock(medicineName = "قريب", quantity = 10.0, minimumQuantity = 1.0, expiryDate = today.plusDays(30).toString())
        val later = soon.copy(medicineName = "لاحق", expiryDate = today.plusDays(31).toString())
        assertEquals(1, MedicineStockAlertPolicy.alerts(listOf(soon, later), today).size)
    }

    @Test fun healthyBatchDoesNotAlert() {
        val item = MedicineStock(medicineName = "سليم", quantity = 9.0, minimumQuantity = 2.0, expiryDate = today.plusDays(90).toString())
        assertTrue(MedicineStockAlertPolicy.alerts(listOf(item), today).isEmpty())
    }

    @Test fun invalidExpiryIsIgnoredWithoutCrashing() {
        val item = MedicineStock(medicineName = "تاريخ غير صالح", quantity = 0.0, minimumQuantity = 1.0, expiryDate = "bad-date")
        assertTrue(MedicineStockAlertPolicy.alerts(listOf(item), today).isEmpty())
    }
}
