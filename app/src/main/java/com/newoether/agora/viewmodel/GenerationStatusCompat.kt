package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageStatus

/**
 * Compatibility state used by the generation pipeline while the status lifecycle
 * is being consolidated. Kept package-private so existing GenerationManager code
 * resolves the status consistently during compilation.
 */
internal var currentStatus: MessageStatus = MessageStatus.SENDING
