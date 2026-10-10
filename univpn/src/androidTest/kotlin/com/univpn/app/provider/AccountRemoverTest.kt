package com.univpn.app.provider

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.ProviderAccount
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Deleting an account removes its stored secrets (#12). */
@RunWith(AndroidJUnit4::class)
class AccountRemoverTest {

    private lateinit var db: AppDatabase
    private lateinit var credStore: CredentialStore
    private val user = "test-" + UUID.randomUUID()
    private val a1 = "acct-" + UUID.randomUUID()
    private val a2 = "acct-" + UUID.randomUUID()

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        credStore = CredentialStore(ctx)
    }

    @After
    fun tearDown() {
        credStore.delete(a1)
        credStore.delete(a2)
        credStore.deleteWgKeyPair("mullvad", user)
        db.close()
    }

    private suspend fun addAccount(id: String) {
        db.providerAccountDao().upsert(ProviderAccount(id, "mullvad", "****1234", null))
        credStore.save(id, ProviderCredentials(user, ""))
    }

    @Test
    fun delete_removesCredentialsAndKeypair() = runBlocking {
        addAccount(a1)
        credStore.saveWgKeyPair("mullvad", user, "priv", "pub")

        AccountRemover(db.providerAccountDao(), credStore).delete(a1)

        assertNull(db.providerAccountDao().getById(a1))
        assertNull(credStore.load(a1))
        assertNull(credStore.loadWgKeyPair("mullvad", user))
    }

    @Test
    fun delete_keepsKeypairWhileAnotherAccountUsesTheSameLogin() = runBlocking {
        addAccount(a1)
        addAccount(a2)
        credStore.saveWgKeyPair("mullvad", user, "priv", "pub")

        AccountRemover(db.providerAccountDao(), credStore).delete(a1)

        assertNull(credStore.load(a1))
        assertNotNull(credStore.load(a2))
        assertNotNull(credStore.loadWgKeyPair("mullvad", user))
    }
}
