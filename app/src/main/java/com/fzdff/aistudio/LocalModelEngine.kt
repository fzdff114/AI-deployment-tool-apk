package com.fzdff.aistudio

class LocalModelEngine {
    companion object {
        val available: Boolean = try {
            System.loadLibrary("aistudio")
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun nativeLoad(path: String, contextSize: Int, threads: Int): String
    external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float): String
    external fun nativeUnload()
}
