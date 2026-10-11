package com.mavislibrary.app.audio

import com.mavislibrary.app.data.api.ApiParser
import com.mavislibrary.app.data.api.Endpoints
import com.mavislibrary.app.data.api.LibriVoxVersion
import com.mavislibrary.app.data.api.RemoteSource

class AudiobookRepository(private val remote: RemoteSource) {
    suspend fun find(title: String, author: String = "", gutenbergId: Int? = null): List<LibriVoxVersion> {
        require(title.isNotBlank())
        return ApiParser.libriVox(remote.get(Endpoints.LIBRIVOX, Endpoints.libriVox(title, author, gutenbergId))).versions
    }
}
