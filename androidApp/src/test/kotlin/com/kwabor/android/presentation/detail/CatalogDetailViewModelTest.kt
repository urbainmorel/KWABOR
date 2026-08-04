package com.kwabor.android.presentation.detail

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.detail.CatalogDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CatalogDetailViewModelTest {
    @Test
    fun onIntent_relaysTheTypedIntentToTheSharedRuntime() {
        val runtime = FakeCatalogDetailRuntime()
        val scopeJob = SupervisorJob()
        val viewModel = CatalogDetailViewModel(runtime, CoroutineScope(scopeJob))
        val intent = CatalogDetailIntent.Open(TEST_LISTING_ID)

        viewModel.onIntent(intent)

        assertEquals(listOf<CatalogDetailIntent>(intent), runtime.dispatchedIntents)
        assertFalse(runtime.closed)
        assertTrue(scopeJob.isActive)

        scopeJob.cancel()
    }

    @Test
    fun clearingTheLifecycleStore_closesRuntimeAndCancelsScope() {
        val runtime = FakeCatalogDetailRuntime()
        val scopeJob = SupervisorJob()
        val viewModel = CatalogDetailViewModel(runtime, CoroutineScope(scopeJob))
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        val provider = ViewModelProvider(
            owner = owner,
            factory = viewModelFactory {
                initializer { viewModel }
            },
        )
        val storedViewModel = provider[CatalogDetailViewModel::class.java]

        assertSame(viewModel, storedViewModel)

        store.clear()

        assertTrue(runtime.closed)
        assertFalse(scopeJob.isActive)
    }
}

private class FakeCatalogDetailRuntime : CatalogDetailStateRuntime {
    private val mutableState = MutableStateFlow<CatalogDetailUiState>(CatalogDetailUiState.Closed)
    override val state: StateFlow<CatalogDetailUiState> = mutableState
    val dispatchedIntents = mutableListOf<CatalogDetailIntent>()
    var closed: Boolean = false
        private set

    override fun dispatch(intent: CatalogDetailIntent) {
        dispatchedIntents += intent
    }

    override fun close() {
        closed = true
    }
}

private const val TEST_LISTING_ID = "00000000-0000-4000-8000-000000000001"
