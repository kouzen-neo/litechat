package com.localgpt.app.localai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceModelResolverTest {

    @Test
    fun testIsSupportedModelExtension() {
        assertTrue(HuggingFaceModelResolver.isSupportedModelExtension("model.litertlm"))
        assertTrue(HuggingFaceModelResolver.isSupportedModelExtension("model.LITERTLM"))
        assertTrue(HuggingFaceModelResolver.isSupportedModelExtension("model.task"))
        assertTrue(HuggingFaceModelResolver.isSupportedModelExtension("model.bin"))
        assertTrue(HuggingFaceModelResolver.isSupportedModelExtension("model.tflite"))
        assertTrue(HuggingFaceModelResolver.isSupportedModelExtension("model.litertlm?download=true"))
        assertTrue(HuggingFaceModelResolver.isSupportedModelExtension("sub/path/model.litertlm#fragment"))

        org.junit.Assert.assertFalse(HuggingFaceModelResolver.isSupportedModelExtension("model.txt"))
        org.junit.Assert.assertFalse(HuggingFaceModelResolver.isSupportedModelExtension("model.json"))
        org.junit.Assert.assertFalse(HuggingFaceModelResolver.isSupportedModelExtension("README.md"))
    }

    @Test
    fun testDirectNonHfUrl() = runBlocking {
        val url = "https://example.com/downloads/gemma-model.litertlm?query=1"
        val result = HuggingFaceModelResolver.resolve(url)
        assertTrue("Expected SingleFile for direct .litertlm URL", result is HuggingFaceModelResolver.ResolveResult.SingleFile)
        val single = result as HuggingFaceModelResolver.ResolveResult.SingleFile
        assertEquals("gemma-model.litertlm", single.file.fileName)
        assertEquals(url, single.file.downloadUrl)
    }

    @Test
    fun testDirectNonHfUrlInvalidExtension() = runBlocking {
        val url = "https://example.com/downloads/model.pdf"
        val result = HuggingFaceModelResolver.resolve(url)
        assertTrue("Expected Error for unsupported extension", result is HuggingFaceModelResolver.ResolveResult.Error)
    }

    @Test
    fun testHfBlobUrlResolution() = runBlocking {
        val url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it-gpu.litertlm"
        val result = HuggingFaceModelResolver.resolve(url)
        assertTrue("Expected SingleFile for blob URL", result is HuggingFaceModelResolver.ResolveResult.SingleFile)
        val single = result as HuggingFaceModelResolver.ResolveResult.SingleFile
        assertEquals("gemma-4-E2B-it-gpu.litertlm", single.file.fileName)
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm?download=true",
            single.file.downloadUrl
        )
    }

    @Test
    fun testHfResolveUrlWithQueryParams() = runBlocking {
        val url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm?download=true"
        val result = HuggingFaceModelResolver.resolve(url)
        assertTrue("Expected SingleFile for resolve URL", result is HuggingFaceModelResolver.ResolveResult.SingleFile)
        val single = result as HuggingFaceModelResolver.ResolveResult.SingleFile
        assertEquals("gemma-4-E2B-it-gpu.litertlm", single.file.fileName)
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm?download=true",
            single.file.downloadUrl
        )
    }

    @Test
    fun testHfShortUrlBlob() = runBlocking {
        val url = "https://hf.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it-gpu.litertlm"
        val result = HuggingFaceModelResolver.resolve(url)
        assertTrue("Expected SingleFile for hf.co blob URL", result is HuggingFaceModelResolver.ResolveResult.SingleFile)
        val single = result as HuggingFaceModelResolver.ResolveResult.SingleFile
        assertEquals("gemma-4-E2B-it-gpu.litertlm", single.file.fileName)
    }

    @Test
    fun testEmptyInput() = runBlocking {
        val result = HuggingFaceModelResolver.resolve("   ")
        assertTrue("Expected Error for blank URL", result is HuggingFaceModelResolver.ResolveResult.Error)
    }
}
