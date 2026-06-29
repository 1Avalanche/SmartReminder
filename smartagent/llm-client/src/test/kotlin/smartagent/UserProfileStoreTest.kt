package smartagent

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserProfileStoreTest {

    private lateinit var tmpDir: File
    private lateinit var profileFile: File

    @Before
    fun setup() {
        tmpDir = createTempDirectory("smartagent-test").toFile()
        profileFile = File(tmpDir, "user_profile.md")
    }

    @After
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load returns empty string when file does not exist`() {
        val store = UserProfileStore(profileFile)
        assertEquals("", store.load())
    }

    @Test
    fun `save writes content to file`() {
        val store = UserProfileStore(profileFile)
        store.save("user likes Kotlin")

        assertTrue(profileFile.exists())
        assertEquals("user likes Kotlin", profileFile.readText())
    }

    @Test
    fun `load returns saved content`() {
        val store = UserProfileStore(profileFile)
        store.save("expert in backend")

        assertEquals("expert in backend", store.load())
    }

    @Test
    fun `clear wipes file content`() {
        val store = UserProfileStore(profileFile)
        store.save("some profile")
        store.clear()

        assertEquals("", store.load())
    }

    @Test
    fun `save overwrites previous content`() {
        val store = UserProfileStore(profileFile)
        store.save("old profile")
        store.save("new profile")

        assertEquals("new profile", store.load())
    }
}
