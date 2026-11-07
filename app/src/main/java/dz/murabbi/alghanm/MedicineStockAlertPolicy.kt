package dz.murabbi.alghanm

import dz.murabbi.alghanm.data.MedicineStock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure, deterministic rules shared by the daily notification receiver and JVM tests. */
internal object MedicineStockAlertPolicy {
    fun alerts(items: List<MedicineStock>, today: LocalDate): List<String> =
        items.mapNotNull { item ->
            val expiry = runCatching { LocalDate.parse(item.expiryDate) }.getOrNull() ?: return@mapNotNull null
            when {
                expiry.isBefore(today) -> "${item.medicineName}: منتهي الصلاحية"
                item.quantity <= item.minimumQuantity ->
                    "${item.medicineName}: المخزون منخفض (${item.quantity} ${item.unit})"
                ChronoUnit.DAYS.between(today, expiry) in 0L..30L ->
                    "${item.medicineName}: تنتهي صلاحيته خلال 30 يوماً"
                else -> null
            }
        }
}
