package com.newoether.agora.api

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide serialization gate for every on-device model operation — local chat
 * generation AND embedding computation.
 *
 * Replaces the former global [com.newoether.agora.automation.GenerationQueue] single
 * slot for the local path. The queue serialized ALL generation (remote included),
 * which needlessly blocked remote parallelism; only local model work actually needs
 * to be mutual-excluded, because a chat model held resident by [LocalProvider] plus a
 * concurrently-loaded embedding model (see [LlamaEngine.computeEmbeddings], which
 * loads+frees a model per call) can exceed the native heap and OOM the process.
 *
 * Holders:
 *  • [LocalProvider.generateResponse] wraps each native generation turn.
 *  • [LlamaEngine.computeEmbeddings] wraps its load→compute→free cycle.
 *  • [com.newoether.agora.viewmodel.MessageGenerationController.generateTitle] wraps
 *    the local title-generation turn.
 *
 * [Mutex.withLock] is cancellable, so a Stop releases the slot immediately and the
 * next local generation can proceed without waiting for the cancelled one to unwind.
 */
object LocalModelSerializer {
    val mutex: Mutex = Mutex()
}
