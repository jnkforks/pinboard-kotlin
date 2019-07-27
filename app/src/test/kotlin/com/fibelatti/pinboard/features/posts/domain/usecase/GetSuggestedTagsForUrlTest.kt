package com.fibelatti.pinboard.features.posts.domain.usecase

import com.fibelatti.core.functional.Failure
import com.fibelatti.core.functional.Success
import com.fibelatti.core.functional.exceptionOrNull
import com.fibelatti.core.functional.getOrNull
import com.fibelatti.core.test.extension.givenSuspend
import com.fibelatti.core.test.extension.mock
import com.fibelatti.core.test.extension.shouldBe
import com.fibelatti.core.test.extension.shouldBeAnInstanceOf
import com.fibelatti.pinboard.MockDataProvider.createSuggestedTags
import com.fibelatti.pinboard.MockDataProvider.mockUrlValid
import com.fibelatti.pinboard.core.network.ApiException
import com.fibelatti.pinboard.core.network.InvalidRequestException
import com.fibelatti.pinboard.features.posts.domain.PostsRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class GetSuggestedTagsForUrlTest {

    private val mockPostsRepository = mock<PostsRepository>()
    private val mockValidateUrl = mock<ValidateUrl>()

    private val getSuggestedTagsForUrl = GetSuggestedTagsForUrl(
        mockPostsRepository,
        mockValidateUrl
    )

    @Test
    fun `GIVEN ValidateUrl fails WHEN AddPost is called THEN Failure is returned`() {
        // GIVEN
        givenSuspend { mockValidateUrl(mockUrlValid) }
            .willReturn(Failure(InvalidRequestException()))

        // WHEN
        val result = runBlocking { getSuggestedTagsForUrl(mockUrlValid) }

        // THEN
        result.shouldBeAnInstanceOf<Failure>()
        result.exceptionOrNull()?.shouldBeAnInstanceOf<InvalidRequestException>()
    }

    @Test
    fun `GIVEN posts repository add fails WHEN AddPost is called THEN Failure is returned`() {
        // GIVEN
        givenSuspend { mockValidateUrl(mockUrlValid) }
            .willReturn(Success(mockUrlValid))
        givenSuspend { mockPostsRepository.getSuggestedTagsForUrl(mockUrlValid) }
            .willReturn(Failure(ApiException()))

        // WHEN
        val result = runBlocking { getSuggestedTagsForUrl(mockUrlValid) }

        // THEN
        result.shouldBeAnInstanceOf<Failure>()
        result.exceptionOrNull()?.shouldBeAnInstanceOf<ApiException>()
    }

    @Test
    fun `GIVEN posts repository add succeeds WHEN AddPost is called THEN Success is returned`() {
        // GIVEN
        val response = createSuggestedTags()
        givenSuspend { mockValidateUrl(mockUrlValid) }
            .willReturn(Success(mockUrlValid))
        givenSuspend { mockPostsRepository.getSuggestedTagsForUrl(mockUrlValid) }
            .willReturn(Success(response))

        // WHEN
        val result = runBlocking { getSuggestedTagsForUrl(mockUrlValid) }

        // THEN
        result.getOrNull() shouldBe response
    }
}
