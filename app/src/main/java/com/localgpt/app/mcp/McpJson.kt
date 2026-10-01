package com.localgpt.app.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** Shared Gson instance for MCP JSON-RPC payloads. */
internal object McpJson {
    val gson: Gson = GsonBuilder().create()
}
