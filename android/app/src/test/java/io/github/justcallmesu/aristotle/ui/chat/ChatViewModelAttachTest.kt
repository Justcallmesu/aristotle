package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.RpcError
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.chat.AttachmentProblem
import io.github.justcallmesu.aristotle.data.chat.AttachmentRules
import io.github.justcallmesu.aristotle.data.chat.AttachmentSource
import io.github.justcallmesu.aristotle.data.chat.AttachmentStatus
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.PickKind
import io.github.justcallmesu.aristotle.data.chat.Preparation
import io.github.justcallmesu.aristotle.data.chat.PreparedAttachment
import io.github.justcallmesu.aristotle.data.chat.Staged
import io.github.justcallmesu.aristotle.data.chat.Upload
import io.github.justcallmesu.aristotle.data.chat.UserAttachment
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T16 in [ChatViewModel]: attach, stage, submit, the per-attachment progress, Retry with the same submission id. */
class ChatViewModelAttachTest {
    private val session = "s1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class Call(val method: String, val params: JsonObject)

    private class FakeLink(private val snapshot: String = IDLE) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 1))
        override val events: SharedFlow<InboundFrame> = MutableSharedFlow(extraBufferCapacity = 64)
        val calls = CopyOnWriteArrayList<Call>()
        var answer: suspend (Call) -> JsonElement = { HermesJson.parseToJsonElement("{}") }

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            if (method == "session.resume") return HermesJson.parseToJsonElement(snapshot)
            if (method == "session.events.since") return HermesJson.parseToJsonElement("""{"events":[],"truncated":true,"snapshot_required":true}""")
            val call = Call(method, params)
            calls += call
            return answer(call)
        }

        override fun respond(requestId: String, result: JsonObject): Boolean = true
        override fun retryNow() = Unit
        fun methods() = calls.map { it.method }
        fun sent(method: String) = calls.filter { it.method == method }
    }

    private class FakeSource : AttachmentSource {
        val results = HashMap<String, Preparation>()
        val bytes = HashMap<String, ByteArray>()
        val discarded = mutableListOf<String>()

        fun ready(uri: String, name: String, image: Boolean, sha: String = "c".repeat(64)) {
            results[uri] = Preparation.Ready(PreparedAttachment("h-$uri", name, if (image) "image/jpeg" else "application/pdf", 3, sha, image))
            bytes["h-$uri"] = byteArrayOf(1, 2, 3)
        }

        override suspend fun prepare(uri: String, kind: PickKind): Preparation = results.getValue(uri)
        override suspend fun read(handle: String): ByteArray? = bytes[handle]
        override fun discard(handle: String) {
            discarded += handle
        }
        override fun cameraTarget(): String = "content://io.github.justcallmesu.aristotle.files/camera/x.jpg"
    }

    private fun json(text: String) = HermesJson.parseToJsonElement(text)

    private val staged = """{"attached":true,"path":"/h/cache/images/upload_1.jpg","mime":"image/jpeg","name":"upload_1.jpg","count":1,"bytes":3}"""
    private val filed = """{"attached":true,"name":"report.pdf","path":"/h/attachments/report.pdf","ref_path":"/h/attachments/report.pdf","ref_text":"@file:/h/attachments/report.pdf","uploaded":true}"""
    private fun receipt(admission: String) = json("""{"admission_id":"$admission","ref":{"profile_id":"/h","session_id":"s1"},"status":"started","execution_generation":null}""")

    /** Hermes answers each call by method; [failures] answer the first calls of a method by throwing. */
    private fun hermes(link: FakeLink, vararg failures: Pair<String, RpcException>) {
        val pending = failures.toMutableList()
        link.answer = { call ->
            pending.firstOrNull { it.first == call.method }?.let {
                pending.remove(it)
                throw it.second
            }
            when (call.method) {
                "image.attach_bytes" -> json(staged)
                "file.attach" -> json(filed)
                "prompt.submit" -> receipt("a1")
                else -> json("{}")
            }
        }
    }

    private var now = 0L
    private var ids = 0
    private val source = FakeSource()

    private fun viewModel(link: FakeLink) =
        ChatViewModel(session, link, ResumedSessions(), ChatDrafts(MemoryStore()), background = Dispatchers.Unconfined, scope = scope, clock = { now }, newId = { "id-${++ids}" }, attachments = source)
            .also { now = 1_000 }

    private fun looked() {
        now += ComposerLogic.ARM_MS
    }

    private fun ChatViewModel.composer() = uiState.value.composer

    /** A photo (image) and a document (file) picked, then [text] typed; ids: attachments id-1, id-2. */
    private fun ChatViewModel.picked(text: String = "hello") {
        source.ready("content://photo/1", "IMG_1.jpg", image = true)
        source.ready("content://doc/2", "report.pdf", image = false)
        attach(listOf("content://photo/1"), PickKind.Photo)
        attach(listOf("content://doc/2"), PickKind.File)
        editDraft(text)
        looked()
    }

    private fun Call.text(key: String) = (params[key] as JsonPrimitive).content

    @Test
    fun sendStagesEachAttachmentInOrderThenSubmitsWithHermesAnswers() {
        val link = FakeLink().also { hermes(it) }
        val vm = viewModel(link)
        vm.picked()
        assertEquals(listOf(AttachmentStatus.Ready, AttachmentStatus.Ready), vm.composer().attachments.map { it.status })
        vm.send()
        assertEquals(listOf("image.attach_bytes", "file.attach", "prompt.submit"), link.methods())
        assertEquals(json("""{"session_id":"s1","content_base64":"AQID","filename":"IMG_1.jpg"}"""), link.sent("image.attach_bytes").single().params)
        assertEquals(json("""{"session_id":"s1","name":"report.pdf","data_url":"data:application/pdf;base64,AQID"}"""), link.sent("file.attach").single().params)
        assertEquals(
            json(
                """{"session_id":"s1","text":"@file:/h/attachments/report.pdf\n\nhello","submission_id":"id-3",
                "attachments":[{"path":"/h/cache/images/upload_1.jpg","mime":"image/jpeg"}]}""",
            ),
            link.sent("prompt.submit").single().params,
        )
        assertTrue(vm.composer().attachments.isEmpty())
        val sent = vm.composer().outbox.single()
        assertEquals(OutgoingStatus.Sent("a1", 1), sent.status)
        assertEquals(listOf(Upload.Sent, Upload.Sent), sent.attachments.map { it.upload })
    }

    @Test
    fun imagesWithoutWordsAskWhatIsInTheImageAsTheDesktopDoes() {
        val link = FakeLink().also { hermes(it) }
        val vm = viewModel(link)
        source.ready("content://photo/1", "IMG_1.jpg", image = true)
        vm.attach(listOf("content://photo/1"), PickKind.Camera)
        looked()
        vm.send()
        assertEquals("What do you see in this image?", link.sent("prompt.submit").single().text("text"))
        assertEquals("", vm.composer().outbox.single().text)
    }

    @Test
    fun aFailedUploadSendsNoPromptAndRetryGoesOnWithTheSameSubmissionId() {
        val link = FakeLink().also { hermes(it, "file.attach" to RpcException(null, "no answer")) }
        val vm = viewModel(link)
        vm.picked()
        vm.send()
        assertEquals(listOf("image.attach_bytes", "file.attach"), link.methods())
        val failed = vm.composer().outbox.single()
        assertEquals(SendFailure.AttachFailed("report.pdf", AttachCause.NotReached), (failed.status as OutgoingStatus.Failed).failure)
        assertEquals(listOf(Upload.Sent, Upload.Failed), failed.attachments.map { it.upload })
        assertTrue(failed.retryable)

        looked()
        vm.retrySend(failed.id)
        // The image Hermes already staged is not uploaded again; the file is, then the prompt with the same id.
        assertEquals(listOf("image.attach_bytes", "file.attach", "file.attach", "prompt.submit"), link.methods())
        assertEquals("id-3", link.sent("prompt.submit").single().text("submission_id"))
        assertEquals(OutgoingStatus.Sent("a1", 1), vm.composer().outbox.single().status)
    }

    @Test
    fun hermesRefusingAnUploadSaysSoInItsWords() {
        val link = FakeLink().also { hermes(it) }
        link.answer = { call -> if (call.method == "image.attach_bytes") json("""{"attached":false,"message":"unsupported image"}""") else json("{}") }
        val vm = viewModel(link)
        vm.picked()
        vm.send()
        val failure = (vm.composer().outbox.single().status as OutgoingStatus.Failed).failure
        assertEquals(SendFailure.AttachFailed("IMG_1.jpg", AttachCause.Refused, "unsupported image"), failure)
        assertEquals(listOf("image.attach_bytes"), link.methods())
    }

    @Test
    fun aPromptWithNoAnswerIsRetriedWithTheSameIdAndTheSameStagedAttachments() {
        val link = FakeLink().also { hermes(it, "prompt.submit" to RpcException(null, "no answer")) }
        val vm = viewModel(link)
        vm.picked()
        vm.send()
        val failed = vm.composer().outbox.single()
        assertEquals(SendFailure.NoAnswer, (failed.status as OutgoingStatus.Failed).failure)
        looked()
        vm.retrySend(failed.id)
        val submits = link.sent("prompt.submit")
        assertEquals(2, submits.size)
        assertEquals(submits[0].params, submits[1].params)
        assertEquals(1, link.sent("image.attach_bytes").size)
        assertEquals(1, link.sent("file.attach").size)
    }

    @Test
    fun aRefusedPromptStagesItsImagesAgainButKeepsTheFileTextSoTheIdStillMatches() {
        val link = FakeLink().also { hermes(it, "prompt.submit" to RpcException(RpcError(4001, "invalid_params"), "prompt.submit failed: 4001")) }
        val vm = viewModel(link)
        vm.picked()
        vm.send()
        val failed = vm.composer().outbox.single()
        assertEquals(null, failed.attachments.first().staged)
        assertEquals(Staged.File("@file:/h/attachments/report.pdf"), failed.attachments.last().staged)
        looked()
        vm.retrySend(failed.id)
        assertEquals(listOf("image.attach_bytes", "file.attach", "prompt.submit", "image.attach_bytes", "prompt.submit"), link.methods())
        val submits = link.sent("prompt.submit")
        assertEquals(submits[0].params, submits[1].params)
    }

    @Test
    fun nothingGoesWhileAnItemIsPreparedOrRefusedAndRemovingOneDiscardsItsBytes() {
        val link = FakeLink().also { hermes(it) }
        val vm = viewModel(link)
        source.results["content://big"] = Preparation.Refused("huge.mov", AttachmentProblem.TooLarge(AttachmentRules.MAX_PAYLOAD_BYTES, 9_000_000))
        vm.attach(listOf("content://big"), PickKind.File)
        vm.editDraft("hi")
        looked()
        assertTrue(ComposerLogic.hasRefused(vm.composer()))
        vm.send()
        vm.queue()
        assertTrue(link.calls.isEmpty())
        vm.removeAttachment(vm.composer().attachments.single().id)
        assertTrue(source.discarded.isEmpty())

        source.ready("content://photo/1", "IMG_1.jpg", image = true)
        vm.attach(listOf("content://photo/1"), PickKind.Photo)
        vm.removeAttachment(vm.composer().attachments.single().id)
        assertEquals(listOf("h-content://photo/1"), source.discarded)
        assertTrue(vm.composer().attachments.isEmpty())
    }

    @Test
    fun atMostTenAttachmentsAndTheUserIsTold() {
        val vm = viewModel(FakeLink())
        val uris = (1..12).map { "content://photo/$it" }
        uris.forEach { source.ready(it, "$it.jpg", image = true) }
        vm.attach(uris, PickKind.Photo)
        assertEquals(AttachmentRules.MAX_ATTACHMENTS, vm.composer().attachments.size)
        assertEquals(ComposerMessage.TooManyAttachments(10), vm.composer().message)
    }

    @Test
    fun aSteerCarriesWordsOnlySoItIsOffWithAttachments() {
        val link = FakeLink(BUSY).also { hermes(it) }
        val vm = viewModel(link)
        vm.picked()
        vm.steer()
        assertTrue(link.sent("session.steer").isEmpty())
        vm.composer().attachments.map { it.id }.forEach(vm::removeAttachment)
        looked()
        vm.steer()
        assertEquals("hello", link.sent("session.steer").single().text("text"))
    }

    @Test
    fun dismissingANoAnswerPutsWordsAndStagedAttachmentsBackAndSendingAgainIsTheSameAdmission() {
        val link = FakeLink().also { hermes(it, "prompt.submit" to RpcException(null, "no answer")) }
        val vm = viewModel(link)
        vm.picked()
        vm.send()
        looked()
        vm.dismissFailed("id-3")
        assertEquals("hello", vm.composer().draft)
        assertEquals(listOf("IMG_1.jpg", "report.pdf"), vm.composer().attachments.map { it.name })
        assertTrue(vm.composer().attachments.all { it.staged != null })
        looked()
        vm.send()
        val submits = link.sent("prompt.submit")
        assertEquals(submits[0].params, submits[1].params)
        assertEquals(1, link.sent("image.attach_bytes").size)
    }

    @Test
    fun theSentImagesShowOnHermesOwnRowOfTheTurn() {
        val link = FakeLink().also { hermes(it) }
        val vm = viewModel(link)
        vm.picked()
        vm.send()
        val row = ChatItem.User.of("user-a1", "@file:/h/attachments/report.pdf\n\nhello")
        val shown = ComposerLogic.userAttachments(row, vm.composer())
        assertEquals(listOf(UserAttachment.Kind.File, UserAttachment.Kind.Image), shown.map { it.kind })
        assertEquals("c".repeat(64), shown.last().sha256)
        // Another turn's row gets nothing extra.
        assertEquals(emptyList<UserAttachment>(), ComposerLogic.userAttachments(ChatItem.User("user-a2", "x"), vm.composer()))
    }

    @Test
    fun theCameraTargetComesFromTheSource() {
        assertEquals("content://io.github.justcallmesu.aristotle.files/camera/x.jpg", viewModel(FakeLink()).cameraTarget())
    }

    private companion object {
        const val IDLE = """{"session_id":"s1","messages":[],"running":false,"replay_epoch":"e","last_sequence":3,"prompts":[],"open_requests":[],
            "pending":[],"execution_generation":5,"info":{}}"""
        const val BUSY = """{"session_id":"s1","messages":[{"role":"user","content":"Count","row_id":1}],"running":true,"replay_epoch":"e","last_sequence":3,
            "prompts":[],"open_requests":[],"execution_generation":6,"info":{},
            "pending":[{"admission_id":"a0","input_id":"i0","status":"started","text":"Count"}]}"""
    }
}
