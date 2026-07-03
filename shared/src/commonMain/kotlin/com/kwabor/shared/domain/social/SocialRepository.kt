package com.kwabor.shared.domain.social

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult

interface SocialRepository {
    suspend fun getFeed(page: PageRequest = PageRequest()): DomainResult<PageResult<SocialPost>>

    suspend fun createPost(draft: SocialPostDraft): DomainResult<SocialPost>

    suspend fun likePost(postId: String): DomainResult<Unit>

    suspend fun unlikePost(postId: String): DomainResult<Unit>

    suspend fun followUser(userId: String): DomainResult<Unit>

    suspend fun unfollowUser(userId: String): DomainResult<Unit>
}
