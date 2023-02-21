package net.kyori.adventure.webui.jvm.minimessage

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import net.juligames.adventure.webui.WebUICoreAdapter
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.webui.*
import net.kyori.adventure.webui.jvm.appendComponent
import net.kyori.adventure.webui.jvm.getConfigString
import net.kyori.adventure.webui.jvm.minimessage.editor.installEditor
import net.kyori.adventure.webui.jvm.minimessage.hook.*
import net.kyori.adventure.webui.jvm.minimessage.storage.BytebinStorage
import net.kyori.adventure.webui.websocket.*
import java.time.Instant
import java.util.*

private val startedAt = Instant.now()

public val Placeholders?.tagResolver: TagResolver
    get() {
        if (this == null) return TagResolver.empty()
        val stringConverted =
            this.stringPlaceholders?.map { (key, value) ->
                try {
                    Placeholder.parsed(key, value)
                } catch (_: java.lang.IllegalArgumentException) {
                    null
                }
            } ?: listOf()
        val componentConverted =
            this.componentPlaceholders?.map { (key, value) ->
                try {
                    Placeholder.component(key, GsonComponentSerializer.gson().deserialize(value.toString()))
                } catch (_: java.lang.IllegalArgumentException) {
                    null
                }
            } ?: listOf()
        val coreResolver: Optional<TagResolver> = WebUICoreAdapter.compileResolver();
        return TagResolver.resolver(
            (stringConverted + componentConverted +
                    coreResolver.orElse(TagResolver.empty())).filterNotNull()
        )
    }

/** Entry-point for MiniMessage Viewer. */
public fun Application.miniMessage() {
    // add standard renderers
    HookManager.apply {
        component(HOVER_EVENT_RENDER_HOOK)
        component(CLICK_EVENT_RENDER_HOOK)
        component(INSERTION_RENDER_HOOK)
        component(COMPONENT_CLASS_RENDER_HOOK)
        component(TEXT_COLOR_RENDER_HOOK)
        component(TEXT_DECORATION_RENDER_HOOK)
        component(FONT_RENDER_HOOK)
        component(TEXT_RENDER_HOOK, 500) // content needs to be set last
    }

    //Start core
    WebUICoreAdapter.startCore();

    BytebinStorage.BYTEBIN_INSTANCE = this.getConfigString("bytebinInstance")

    routing {
        // define static path to resources
        static("") {
            resources("web")
            defaultResource("web/index.html")

            val script = this@miniMessage.getConfigString("jsScriptFile")
            resource("js/main.js", script)
            resource("js/$script.map", "$script.map")
        }

        // set up other routing
        route(URL_API) {
            webSocket(URL_MINI_TO_HTML) {
                var tagResolver = TagResolver.empty()
                var miniMessage: String? = null
                var isolateNewlines = false

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        when (val packet = Serializers.json.tryDecodeFromString<Packet>(frame.readText())) {
                            is Call -> {
                                miniMessage = packet.miniMessage
                                isolateNewlines = packet.isolateNewlines
                            }

                            is Placeholders -> tagResolver = packet.tagResolver
                            null -> continue
                        }

                        if (miniMessage == null) continue
                        val response =
                            try {
                                val result = StringBuilder()

                                if (isolateNewlines) {
                                    miniMessage
                                        .split("\n")
                                        .map { line -> HookManager.render(line) }
                                        .map { line ->
                                            MiniMessage.miniMessage()
                                                .deserialize(line, tagResolver)
                                        }
                                        .map { component -> HookManager.render(component) }
                                        .forEach { component ->
                                            result.appendComponent(component)
                                            result.append("\n")
                                        }
                                } else {
                                    val component = MiniMessage.miniMessage()
                                        .deserialize(HookManager.render(miniMessage), tagResolver)
                                    result.appendComponent(HookManager.render(component))
                                }

                                Response(ParseResult(true, result.toString()))
                            } catch (e: Exception) {
                                Response(
                                    ParseResult(
                                        false, errorMessage = e.message ?: "Unknown error!"
                                    )
                                )
                            }

                        outgoing.send(Frame.Text(Serializers.json.encodeToString(response)))
                    }
                }
            }

            post(URL_MINI_TO_JSON) {
                val structure = Serializers.json.tryDecodeFromString<Combined>(call.receiveText())
                val input = structure?.miniMessage ?: return@post
                call.respondText(
                    GsonComponentSerializer.gson()
                        .serialize(
                            MiniMessage.miniMessage()
                                .deserialize(input, structure.placeholders.tagResolver)
                        )
                )
            }

            post(URL_MINI_TO_TREE) {
                val structure = Serializers.json.tryDecodeFromString<Combined>(call.receiveText())
                val input = structure?.miniMessage ?: return@post
                val resolver = structure.placeholders.tagResolver
                val root = MiniMessage.miniMessage().deserializeToTree(input, resolver)
                call.respondText(root.toString())
            }

            post(URL_MINI_SHORTEN) {
                val structure = Serializers.json.tryDecodeFromString<Combined>(call.receiveText())
                val code = BytebinStorage.bytebinStore(structure ?: return@post)
                if (code != null) {
                    call.respondText(code)
                } else {
                    call.response.status(HttpStatusCode.InternalServerError)
                }
            }

            get(URL_MINI_SHORTEN) {
                val code = call.parameters["code"]
                val structure = BytebinStorage.bytebinLoad(code ?: return@get)
                if (structure != null) {
                    // Pretty sure this is pointlessly decoding from json and then re-encoding to the same thing
                    call.respondText(Serializers.json.encodeToString(structure))
                } else {
                    call.response.status(HttpStatusCode.NotFound)
                }
            }

            get(URL_BUILD_INFO) {
                val info = BuildInfo(
                    startedAt = startedAt.toString(),
                    version = this@miniMessage.getConfigString("miniMessageVersion"),
                    commit = this@miniMessage.getConfigString("commitHash"),
                    bytebinInstance = BytebinStorage.BYTEBIN_INSTANCE,
                )
                call.respondText(Serializers.json.encodeToString(info))
            }

            route(URL_EDITOR) { installEditor() }
        }
    }
}
