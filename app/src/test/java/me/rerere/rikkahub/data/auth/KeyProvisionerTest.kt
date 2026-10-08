package me.rerere.rikkahub.data.auth

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.api.gateway.*
import me.rerere.rikkahub.data.model.gateway.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class KeyProvisionerTest {
    private class Store(var routing: GatewayRouting = GatewayRouting()) : GatewayCatalogStore {
        var applied: GatewayRouting? = null
        override suspend fun read() = routing
        override suspend fun save(routing: GatewayRouting) { this.routing = routing }
        override suspend fun apply(routing: GatewayRouting) { applied = routing }
        override suspend fun clear() { routing = GatewayRouting(); applied = routing }
    }

    private class Fixture {
        val groups = mutableListOf(GatewayGroup(29, "Chat"), GatewayGroup(2, "Image"),
            GatewayGroup(23, "Other chat"), GatewayGroup(13, "Other image"), GatewayGroup(30, "Other"))
        val keys = mutableListOf<ApiKeyDto>()
        val creations = mutableListOf<CreateKeyRequest>()
        val modelRequests = mutableListOf<String>()
        var modelBody = """{"data":[{"id":"new-model"}]}"""
        var modelStatus = 200
        var failGroups = false
        var failKeys = false
        var pages = 0
        val store = Store()
        val api = Proxy.newProxyInstance(BingoGatewayAPI::class.java.classLoader, arrayOf(BingoGatewayAPI::class.java)) { _, method, args ->
            when (method.name) {
                "availableGroups" -> {
                    check(!failGroups) { "offline" }
                    GatewayEnvelope(data = groups.toList())
                }
                "listKeys" -> {
                    check(!failKeys) { "offline" }
                    val page = args[0] as Int
                    pages++
                    // Deliberately small server pages to exercise pagination even when the client asks for 100.
                    GatewayEnvelope(data = PagedList(keys.drop((page - 1) * 2).take(2), keys.size, page, 2, (keys.size + 1) / 2))
                }
                "createKey" -> {
                    val request = args[0] as CreateKeyRequest
                    creations += request
                    val key = ApiKeyDto(id = (keys.size + 1).toLong(), name = request.name,
                        key = "sk-${request.groupId}-${keys.size}", groupId = request.groupId, status = "active")
                    keys += key
                    GatewayEnvelope(data = key)
                }
                "deleteKey" -> error("Group switching must never delete existing keys")
                else -> error("Unexpected call ${method.name}")
            }
        } as BingoGatewayAPI
        val modelAPI = GatewayModelAPI(OkHttpClient.Builder().addInterceptor { chain ->
            modelRequests += chain.request().header("Authorization").orEmpty()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(modelStatus)
                .message("test").body(modelBody.toResponseBody()).build()
        }.build())
        val provisioner = KeyProvisioner(api, store, modelAPI)
    }

    @Test fun `switch synchronizes using the new group's key and leaves the other purpose unchanged`() = runBlocking {
        val f = Fixture()
        f.provisioner.refresh()
        val image = f.store.routing.image
        f.provisioner.switchGroup(GatewayPurpose.CHAT, 23)
        assertEquals(23, f.store.routing.chat!!.group.id)
        assertEquals(image, f.store.routing.image)
        assertEquals("Bearer ${f.store.routing.chat!!.key}", f.modelRequests.last())
        assertEquals(f.store.routing, f.store.applied)
    }

    @Test fun `failed model fetch never commits a new selection and its key is reused on retry`() = runBlocking {
        val f = Fixture()
        f.provisioner.refresh()
        val original = f.store.routing
        f.modelStatus = 503
        f.provisioner.switchGroup(GatewayPurpose.IMAGE, 13)
        assertEquals(original, f.store.routing)
        assertNotNull(f.provisioner.syncState.value.error)
        f.modelStatus = 200
        f.provisioner.switchGroup(GatewayPurpose.IMAGE, 13)
        assertEquals(1, f.creations.count { it.groupId == 13 })
        assertEquals(original.image, f.store.routing.imageHistory.single())
    }

    @Test fun `permission is rechecked before creating a key`() = runBlocking {
        val f = Fixture()
        f.provisioner.refresh()
        val original = f.store.routing
        f.groups.removeAll { it.id == 23 }
        f.provisioner.switchGroup(GatewayPurpose.CHAT, 23)
        assertEquals(original, f.store.routing)
        assertFalse(f.creations.any { it.groupId == 23 })
    }

    @Test fun `revoked selected group is cleared without choosing a different billing group`() = runBlocking {
        val f = Fixture()
        f.provisioner.refresh()
        f.groups.removeAll { it.id == 29 }
        f.provisioner.refresh()
        assertEquals(29, f.store.routing.chat!!.group.id)
        assertEquals("", f.store.routing.chat!!.key)
        assertTrue(f.store.routing.chat!!.models.isEmpty())
        assertNotNull(f.provisioner.syncState.value.error)
        assertFalse(f.creations.any { it.groupId == 30 })
    }

    @Test fun `malformed catalog preserves cached models but valid empty catalog removes them`() = runBlocking {
        val f = Fixture()
        f.provisioner.refresh()
        val previous = f.store.routing.chat
        f.modelBody = "{}"
        f.provisioner.refresh()
        assertEquals(previous, f.store.routing.chat)
        f.modelBody = """{"data":[]}"""
        f.provisioner.refresh()
        assertTrue(f.store.routing.chat!!.models.isEmpty())
    }

    @Test fun `keys beyond first page and legacy names are reused without mutation`() = runBlocking {
        val f = Fixture()
        f.keys += (1..4).map { ApiKeyDto(it.toLong(), "sk-$it", "unrelated", 30, "active") }
        f.keys += ApiKeyDto(5, "sk-legacy", "app-gpt", 29, "active")
        f.provisioner.refresh()
        assertTrue(f.pages >= 3)
        assertEquals("sk-legacy", f.store.routing.chat!!.key)
        assertFalse(f.creations.any { it.groupId == 29 })
    }

    @Test fun `cached startup restores catalog and throttles network while explicit refresh fetches`() = runBlocking {
        val f = Fixture()
        f.provisioner.refresh()
        f.modelRequests.clear()
        f.provisioner.ensureProvisioned()
        assertTrue(f.modelRequests.isEmpty())
        f.provisioner.refresh()
        assertEquals(2, f.modelRequests.size)
    }

    @Test fun `catalog parser retains unknown models and normalizes duplicates without requiring metadata`() = runBlocking {
        val f = Fixture()
        f.modelBody = """{"data":[{"id":"new","extra":true},{"id":""},{"id":"new"},{"id":"alias","display_name":"Custom"}]}"""
        f.provisioner.refresh()
        assertEquals(listOf("new", "alias"), f.store.routing.chat!!.models.map { it.id })
    }

    @Test fun `logout drops cached groups keys and catalogs`() = runBlocking {
        val f = Fixture()
        f.provisioner.refresh()
        f.provisioner.clearAccount()
        assertEquals(GatewayRouting(), f.store.routing)
        assertTrue(f.provisioner.syncState.value.groups.isEmpty())
    }

    @Test fun `disallowed selections never provision even when the account has permission`() = runBlocking {
        val f = Fixture()
        f.provisioner.refresh()
        val original = f.store.routing
        val requests = f.modelRequests.size
        val creations = f.creations.size
        for (id in listOf(13, 2, 30)) f.provisioner.switchGroup(GatewayPurpose.CHAT, id)
        for (id in listOf(23, 29, 30)) f.provisioner.switchGroup(GatewayPurpose.IMAGE, id)
        assertEquals(original, f.store.routing)
        assertEquals(requests, f.modelRequests.size)
        assertEquals(creations, f.creations.size)
        assertNotNull(f.provisioner.syncState.value.error)
    }

    @Test fun `upgrade disables disallowed cached groups before a failed network refresh`() = runBlocking {
        val f = Fixture()
        val old = GatewayBinding(GatewayGroup(30, "Old"), "sk-old", listOf(GatewayModel("old-model")), 123)
        f.store.routing = GatewayRouting(chat = old, image = old)
        f.failGroups = true
        assertTrue(runCatching { f.provisioner.refresh() }.isFailure)
        assertEquals(30, f.store.routing.chat!!.group.id)
        assertEquals(30, f.store.routing.image!!.group.id)
        assertTrue(f.store.routing.chat!!.key.isEmpty())
        assertTrue(f.store.routing.image!!.models.isEmpty())
        assertEquals(listOf(old), f.store.routing.imageHistory)
        assertEquals(f.store.routing, f.store.applied)
        assertTrue(f.creations.isEmpty())
    }

    @Test fun `revoked permissions clear active credentials even if listing keys fails`() = runBlocking {
        val f = Fixture()
        f.provisioner.refresh()
        val oldImage = f.store.routing.image
        f.groups.removeAll { it.id == 29 || it.id == 2 }
        f.failKeys = true
        assertTrue(runCatching { f.provisioner.refresh() }.isFailure)
        assertTrue(f.store.routing.chat!!.key.isEmpty())
        assertTrue(f.store.routing.image!!.key.isEmpty())
        assertTrue(oldImage in f.store.routing.imageHistory)
        assertEquals(f.store.routing, f.store.applied)
    }

    @Test fun `missing default permissions require a choice rather than selecting a new billing group`() = runBlocking {
        val f = Fixture()
        f.groups.removeAll { it.id == 29 || it.id == 2 }
        f.provisioner.refresh()
        assertNull(f.store.routing.chat)
        assertNull(f.store.routing.image)
        assertTrue(f.creations.isEmpty())
        assertNotNull(f.provisioner.syncState.value.error)
    }
}
