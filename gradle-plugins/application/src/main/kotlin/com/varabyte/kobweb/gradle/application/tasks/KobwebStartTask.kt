@file:Suppress("LeakingThis") // Following official Gradle guidance

package com.varabyte.kobweb.gradle.application.tasks

import com.varabyte.kobweb.common.io.consumeAsync
import com.varabyte.kobweb.common.path.toUnixSeparators
import com.varabyte.kobweb.gradle.application.util.toDisplayText
import com.varabyte.kobweb.gradle.core.tasks.KobwebTask
import com.varabyte.kobweb.server.api.ServerEnvironment
import com.varabyte.kobweb.server.api.ServerStateFile
import com.varabyte.kobweb.server.api.SiteLayout
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

/**
 * Start a Kobweb web server.
 *
 * Note that this task is NOT blocking. It will start a server in the background and then return success immediately.
 *
 * You should execute the [KobwebStopTask] to stop a server started by this task.
 *
 * @param reuseServer If a server is already running, re-use it if possible.
 */
abstract class KobwebStartTask @Inject constructor(
    private val env: ServerEnvironment,
    private val siteLayout: SiteLayout,
    private val reuseServer: Boolean
) : KobwebTask("Start a Kobweb server") {

    @TaskAction
    fun execute() {
        val stateFile = ServerStateFile(kobwebApplication.kobwebFolder)
        stateFile.content?.let { serverState ->
            val alreadyRunningMessage = "A Kobweb server is already running at ${serverState.toDisplayText()}"
            if (serverState.isRunning()) {
                if (!reuseServer) {
                    throw GradleException("$alreadyRunningMessage and cannot be reused for this task.")
                } else if (serverState.env != env) {
                    throw GradleException(
                        alreadyRunningMessage
                            + " but can't be reused because it is using a different environment (want=$env, current=${serverState.env})"
                    )
                } else {
                    println(alreadyRunningMessage)
                }
                return
            }
        }

        val javaHome = System.getProperty("java.home")!!
        val serverJar = KobwebStartTask::class.java.getResourceAsStream("/server.jar")!!.let { stream ->
            File.createTempFile("server", ".jar").apply {
                appendBytes(stream.readAllBytes())
                deleteOnExit()
            }
        }

        val processParams = arrayOf(
            "${javaHome.toUnixSeparators()}/bin/java",
            env.toSystemPropertyParam(),
            siteLayout.toSystemPropertyParam(),
            // See: https://ktor.io/docs/development-mode.html#system-property
            "-Dio.ktor.development=${env == ServerEnvironment.DEV}",
            "-jar",
            serverJar.absolutePath,
        )

        println(
            """
            Starting server by running:
                ${processParams.joinToString(" ")}
            """.trimIndent()
        )
        // Flush above println. Otherwise, it can end up mixed-up in exception reporting below.
        System.out.flush()

        val process = Runtime.getRuntime().exec(
            processParams,
            // Note: We intentionally set envp null here, to inherit our environment. One of the
            // things that gets inherited is the tmp file location, which seems to be particularly
            // important on Windows, as it will otherwise try to create temp files in folders that
            // we don't have permissions to write to... (See #208)
            null,
            kobwebApplication.path.toFile()
        )

        process.inputStream.consumeAsync {
            // We're not observing server output now, but maybe we will in the future.
            // You'd think therefore we should delete this handler, but it actually seems
            // to help avoid the server stalling on startup in Windows.
            // So until we understand the root problem, we'll just leave this in for now.

            // Potentially related discussions:
            // - https://github.com/gradle/gradle/issues/16716
            //   Running a child process from Gradle on Windows and trying to read the stdin
            //   via inheritIO() will cause the waitFor() to hang endlessly. The reason is
            //   most probably that the inheritIO() is not properly piped out from Gradle's
            //   process, causing the stdout buffer to overflow and the child process to block.
            //   The issue is only reproducible on Windows 10, most probably because Windows 10
            //   stdout buffer is rather small.
            // - https://docs.oracle.com/javase/7/docs/api/java/lang/Process.html
            //   Because some native platforms only provide limited buffer size for standard
            //   input and output streams, failure to promptly write the input stream or read
            //   the output stream of the subprocess may cause the subprocess to block, or
            //   even deadlock.
        }

        val errorMessage = StringBuilder()
        process.errorStream.consumeAsync { line -> errorMessage.appendLine(line) }

        while (stateFile.content == null && process.isAlive) {
            Thread.sleep(300)
        }
        stateFile.content?.let { serverState ->
            println("A Kobweb server is now running at ${serverState.toDisplayText()}")
            println()
            println("Run `gradlew kobwebStop` when you're ready to shut it down.")
        } ?: run {
            throw GradleException(buildString {
                append("Unable to start the Kobweb server.")
                if (errorMessage.isNotEmpty()) {
                    append("\n\nError: $errorMessage")
                }
            })
        }
    }
}