package com.fibelatti.pinboard.features.posts.data

import androidx.annotation.VisibleForTesting
import com.fibelatti.core.functional.Result
import com.fibelatti.core.functional.Success
import com.fibelatti.core.functional.catching
import com.fibelatti.core.functional.getOrDefault
import com.fibelatti.core.functional.map
import com.fibelatti.core.functional.mapCatching
import com.fibelatti.core.functional.onSuccess
import com.fibelatti.pinboard.core.AppConfig.API_MAX_LENGTH
import com.fibelatti.pinboard.core.AppConfig.PinboardApiLiterals
import com.fibelatti.pinboard.core.android.ConnectivityInfoProvider
import com.fibelatti.pinboard.core.functional.resultFrom
import com.fibelatti.pinboard.core.network.ApiException
import com.fibelatti.pinboard.core.network.RateLimitRunner
import com.fibelatti.pinboard.core.util.DateFormatter
import com.fibelatti.pinboard.features.posts.data.model.ApiResultCodes
import com.fibelatti.pinboard.features.posts.data.model.GenericResponseDto
import com.fibelatti.pinboard.features.posts.data.model.PostDtoMapper
import com.fibelatti.pinboard.features.posts.data.model.SuggestedTagDtoMapper
import com.fibelatti.pinboard.features.posts.data.model.UpdateDto
import com.fibelatti.pinboard.features.posts.domain.PostsRepository
import com.fibelatti.pinboard.features.posts.domain.model.Post
import com.fibelatti.pinboard.features.posts.domain.model.SuggestedTags
import com.fibelatti.pinboard.features.tags.domain.model.Tag
import com.fibelatti.pinboard.features.user.domain.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PostsDataSource @Inject constructor(
    private val userRepository: UserRepository,
    private val postsApi: PostsApi,
    private val postsDao: PostsDao,
    private val postDtoMapper: PostDtoMapper,
    private val suggestedTagDtoMapper: SuggestedTagDtoMapper,
    private val dateFormatter: DateFormatter,
    private val connectivityInfoProvider: ConnectivityInfoProvider,
    private val rateLimitRunner: RateLimitRunner
) : PostsRepository {

    override suspend fun update(): Result<String> = withContext(Dispatchers.IO) {
        resultFrom { rateLimitRunner.run(postsApi::update) }
            .mapCatching(UpdateDto::updateTime)
    }

    override suspend fun add(
        url: String,
        title: String,
        description: String?,
        private: Boolean?,
        readLater: Boolean?,
        tags: List<Tag>?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        resultFrom {
            postsApi.add(
                url = url,
                title = title.take(API_MAX_LENGTH),
                description = description,
                public = private?.let { if (private) PinboardApiLiterals.NO else PinboardApiLiterals.YES },
                readLater = readLater?.let { if (readLater) PinboardApiLiterals.YES else PinboardApiLiterals.NO },
                tags = tags?.joinToString(PinboardApiLiterals.TAG_SEPARATOR_REQUEST) { it.name }
                    ?.take(API_MAX_LENGTH)
            )
        }.orThrow()
    }

    override suspend fun delete(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        resultFrom { postsApi.delete(url) }
            .orThrow()
    }

    override suspend fun getAllPosts(
        newestFirst: Boolean,
        searchTerm: String,
        tags: List<Tag>?,
        untaggedOnly: Boolean,
        publicPostsOnly: Boolean,
        privatePostsOnly: Boolean,
        readLaterOnly: Boolean,
        countLimit: Int,
        pageLimit: Int,
        pageOffset: Int
    ): Result<Pair<Int, List<Post>>?> = withContext(Dispatchers.IO) {
        val isConnected = connectivityInfoProvider.isConnected()
        val localData by lazy {
            getLocalData(
                newestFirst,
                searchTerm,
                tags,
                untaggedOnly,
                publicPostsOnly,
                privatePostsOnly,
                readLaterOnly,
                countLimit,
                pageLimit,
                pageOffset
            )
        }

        val userLastUpdate = userRepository.getLastUpdate().takeIf { it.isNotBlank() }
        when {
            !isConnected && userLastUpdate != null -> localData
            !isConnected -> Success(null)
            else -> {
                val apiLastUpdate = update().getOrDefault(dateFormatter.nowAsTzFormat())

                if (userLastUpdate != null && userLastUpdate == apiLastUpdate) {
                    localData
                } else {
                    resultFrom { rateLimitRunner.run { postsApi.getAllPosts() } }
                        .mapCatching { posts ->
                            postsDao.deleteAllPosts()
                            postsDao.savePosts(posts)
                        }
                        .map { localData }
                        .onSuccess { userRepository.setLastUpdate(apiLastUpdate) }
                }
            }
        }
    }

    @VisibleForTesting
    fun getLocalDataSize(
        searchTerm: String,
        tags: List<Tag>?,
        untaggedOnly: Boolean,
        publicPostsOnly: Boolean,
        privatePostsOnly: Boolean,
        readLaterOnly: Boolean,
        countLimit: Int
    ): Int {
        return postsDao.getPostCount(
            term = PostsDao.preFormatTerm(searchTerm),
            tag1 = tags.getAndFormat(0),
            tag2 = tags.getAndFormat(1),
            tag3 = tags.getAndFormat(2),
            untaggedOnly = untaggedOnly,
            publicPostsOnly = publicPostsOnly,
            privatePostsOnly = privatePostsOnly,
            readLaterOnly = readLaterOnly,
            limit = countLimit
        )
    }

    @VisibleForTesting
    fun getLocalData(
        newestFirst: Boolean,
        searchTerm: String,
        tags: List<Tag>?,
        untaggedOnly: Boolean,
        publicPostsOnly: Boolean,
        privatePostsOnly: Boolean,
        readLaterOnly: Boolean,
        countLimit: Int,
        pageLimit: Int,
        pageOffset: Int
    ): Result<Pair<Int, List<Post>>?> {
        return catching {
            val localDataSize = getLocalDataSize(
                searchTerm,
                tags,
                untaggedOnly,
                publicPostsOnly,
                privatePostsOnly,
                readLaterOnly,
                countLimit
            )

            if (localDataSize > 0) {
                localDataSize to postsDao.getAllPosts(
                    newestFirst = newestFirst,
                    term = PostsDao.preFormatTerm(searchTerm),
                    tag1 = tags.getAndFormat(0),
                    tag2 = tags.getAndFormat(1),
                    tag3 = tags.getAndFormat(2),
                    untaggedOnly = untaggedOnly,
                    publicPostsOnly = publicPostsOnly,
                    privatePostsOnly = privatePostsOnly,
                    readLaterOnly = readLaterOnly,
                    limit = pageLimit,
                    offset = pageOffset
                ).let(postDtoMapper::mapList)
            } else {
                null
            }
        }
    }

    private fun List<Tag>?.getAndFormat(index: Int): String {
        return this?.getOrNull(index)?.name?.let(PostsDao.Companion::preFormatTag).orEmpty()
    }

    override suspend fun getPost(url: String): Result<Post> = withContext(Dispatchers.IO) {
        resultFrom { postsApi.getPost(url) }
            .mapCatching { postDtoMapper.map(it.posts.first()) }
    }

    override suspend fun searchExistingPostTag(tag: String): Result<List<String>> {
        return resultFrom {
            val concatenatedTags = withContext(Dispatchers.IO) {
                postsDao.searchExistingPostTag(PostsDao.preFormatTag(tag))
            }

            concatenatedTags.flatMap { it.split(" ") }
                .filter { it.startsWith(tag) }
                .distinct()
                .sorted()
        }
    }

    override suspend fun getSuggestedTagsForUrl(
        url: String
    ): Result<SuggestedTags> = withContext(Dispatchers.IO) {
        resultFrom { rateLimitRunner.run { postsApi.getSuggestedTagsForUrl(url) } }
            .mapCatching(suggestedTagDtoMapper::map)
    }

    private fun Result<GenericResponseDto>.orThrow() = mapCatching {
        if (it.resultCode != ApiResultCodes.DONE.code) throw ApiException()
    }
}