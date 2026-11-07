package dz.murabbi.alghanm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dz.murabbi.alghanm.data.FarmDatabase
import dz.murabbi.alghanm.data.MedicineStock
import dz.murabbi.alghanm.data.VaccineDoseRecord
import dz.murabbi.alghanm.data.AnimalCareStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FarmDatabaseInstrumentedTest {
    private lateinit var database: FarmDatabase

    @Before fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun closeDatabase() { database.close() }

    @Test fun roomCreatesCurrentSchemaAndIndexesOnAndroidSqlite() {
        val sqlite = database.openHelper.writableDatabase
        sqlite.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
        sqlite.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10, cursor.getInt(0))
        }
        sqlite.query("PRAGMA index_list('medicine_stock')").use { cursor ->
            val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            assertTrue(names.contains("index_medicine_stock_expiryDate"))
            assertTrue(names.contains("index_medicine_stock_medicineName"))
        }
    }

    @Test fun medicineStockAndAuditCommitTogetherAndInvalidRowsAreRejected() = runBlocking {
        val dao = database.farmDao()
        val good = MedicineStock(medicineName = "دواء اختبار", quantity = 5.0,
            minimumQuantity = 2.0, expiryDate = "2027-01-01")
        assertTrue(dao.addMedicineStockSafely(good))
        val snapshot = dao.medicineStockOnce()
        assertEquals(1, snapshot.size)
        assertEquals(5.0, snapshot.single().quantity, 0.0)

        val invalid = good.copy(medicineName = " ", quantity = -1.0)
        assertFalse(dao.addMedicineStockSafely(invalid))
        assertEquals(1, dao.medicineStockOnce().size)

        val auditCount = database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM audit_entries").use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, auditCount)
    }

    @Test fun vaccineAndCareChangesWriteAuditRowsTransactionally() = runBlocking {
        val dao = database.farmDao()
        dao.addVaccineRecordAudited(VaccineDoseRecord(
            animalTag = "A-1", vaccineKey = "ppr", vaccineName = "PPR",
            administeredDate = "2026-11-07"
        ))
        dao.saveAnimalCareStatusAudited(AnimalCareStatus(animalTag = "A-1", status = "متابعة"))
        val count = database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM audit_entries").use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(2, count)
    }
}
