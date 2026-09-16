package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
import kotlinx.coroutines.flow.Flow

class TransferRepository(private val database: AppDatabase) {

    val allTransfers: Flow<List<TransferEntity>> = database.transferDao().getAllTransfers()
    val activeTransfers: Flow<List<TransferEntity>> = database.transferDao().getActiveTransfers()

    fun observeTransfer(id: String): Flow<TransferEntity?> =
        database.transferDao().observeTransferById(id)

    suspend fun getTransfer(id: String): TransferEntity? =
        database.transferDao().getTransferById(id)

    suspend fun insertTransfer(transfer: TransferEntity) {
        database.transferDao().insertTransfer(transfer)
    }

    suspend fun updateTransfer(transfer: TransferEntity) {
        database.transferDao().updateTransfer(transfer)
    }

    suspend fun deleteTransfer(id: String) {
        database.transferDao().deleteItemsForTransfer(id)
        database.transferDao().deleteTransferById(id)
    }

    suspend fun clearAll() {
        database.transferDao().clearAllTransfers()
    }

    fun getItemsForTransfer(transferId: String): Flow<List<TransferItemEntity>> =
        database.transferDao().getItemsForTransfer(transferId)

    suspend fun getItemsForTransferSync(transferId: String): List<TransferItemEntity> =
        database.transferDao().getItemsForTransferSync(transferId)

    suspend fun insertItems(items: List<TransferItemEntity>) {
        database.transferDao().insertItems(items)
    }

    suspend fun updateItem(item: TransferItemEntity) {
        database.transferDao().updateItem(item)
    }
}
