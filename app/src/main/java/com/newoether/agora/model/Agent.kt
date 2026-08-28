package com.newoether.agora.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A multi-agent collaborator: a named role with its own system prompt and model, so that
 * several AIs can work together on one conversation.
 *
 * - [name]: the display name shown on the message bubble (authorship label).
 * - [rolePrompt]: this agent's system prompt / role description. It is injected as the
 *   system message for that agent's generation, so each AI has a distinct specialty.
 * - [providerKey]: `"ProviderName:modelId"` deciding which model/provider this agent uses
 *   (same format as the app's model IDs, e.g. `"OpenAI:gpt-4o"`).
 * - [enabled]: whether the agent participates in multi-agent sends.
 */
@Serializable
data class Agent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val rolePrompt: String,
    val providerKey: String,
    val enabled: Boolean = true,
)
