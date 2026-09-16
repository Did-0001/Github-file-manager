package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    fun getAllTransfers(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE status IN ('QUEUED', 'SCANNING', 'VALIDATING', 'PREPARING', 'UPLOADING', 'DOWNLOADING', 'COMMITTING', 'VERIFYING', 'PAUSED') ORDER BY createdAt ASC")
    fun getActiveTransfers(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun getTransferById(id: String): TransferEntity?

    @Query("SELECT * FROM transfers WHERE id = :id")
    fun observeTransferById(id: String): Flow<TransferEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: TransferEntity)

    @Update
    suspend fun updateTransfer(transfer: TransferEntity)

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun deleteTransferById(id: String)

    @Query("DELETE FROM transfers")
    suspend fun clearAllTransfers()

    // Items
    @Query("SELECT * FROM transfer_items WHERE transferId = :transferId")
    fun getItemsForTransfer(transferId: String): Flow<List<TransferItemEntity>>

    @Query("SELECT * FROM transfer_items WHERE transferId = :transferId")
    suspend fun getItemsForTransferSync(transferId: String): List<TransferItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<TransferItemEntity>)

    @Update
    suspend fun updateItem(item: TransferItemEntity)

    @Query("DELETE FROM transfer_items WHERE transferId = :transferId")
    suspend fun deleteItemsForTransfer(transferId: String)
}
