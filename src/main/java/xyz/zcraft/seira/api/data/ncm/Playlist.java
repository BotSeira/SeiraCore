package xyz.zcraft.seira.api.data.ncm;

import java.util.List;

public record Playlist(
        Long id, String name, Long coverImgID, String coverImgURL, String coverImgIDStr, Long adType,
        Long userID, Long createTime, Long status, Boolean opRecommend, Boolean highQuality,
        Boolean newImported, Long updateTime, Long trackCount, Long specialType, Long privacy,
        Long trackUpdateTime, String commentThreadID, Long playCount, Long trackNumberUpdateTime,
        Long subscribedCount, Long cloudTrackCount, Boolean ordered, Object description,
        List<Object> tags, Object updateFrequency, Long backgroundCoverID, Object backgroundCoverURL,
        Long titleImage, Object titleImageURL, Object detailPageTitle, Object englishTitle,
        Object officialPlaylistType, Boolean copied, Object relateResType, Long coverStatus, Boolean mix,
        Object bannedTrackIDS, Object mvResourceInfos, Long shareCount, Long commentCount,
        Object remixVideo, Object newDetailPageRemixVideo, Object sharedUsers, Object historySharedUsers,
        String gradeStatus, Object score, Object algTags, List<Object> distributeTags, Long trialMode,
        Object displayTags, Boolean displayUserInfoAsTagOnly, String playlistType, String uiPlaylistType,
        Boolean mixPodcastPlaylist, Long podcastTrackCount, Long founded, List<String> members) {
}
