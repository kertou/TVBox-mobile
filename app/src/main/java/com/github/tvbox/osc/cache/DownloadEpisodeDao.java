package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface DownloadEpisodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(DownloadEpisode episode);

    @Update
    int update(DownloadEpisode episode);

    @Delete
    int delete(DownloadEpisode episode);

    @Query("select * from downloadEpisode where id = :id")
    DownloadEpisode get(int id);

    @Query("select * from downloadEpisode order by createTime desc")
    List<DownloadEpisode> getAll();

    @Query("select * from downloadEpisode where sourceKey = :sourceKey and vodId = :vodId and flag = :flag order by episodeIndex asc")
    List<DownloadEpisode> getByVod(String sourceKey, String vodId, String flag);

    @Query("select * from downloadEpisode where sourceKey = :sourceKey and vodId = :vodId and flag = :flag and episodeIndex = :episodeIndex limit 1")
    DownloadEpisode getByEpisode(String sourceKey, String vodId, String flag, int episodeIndex);

    @Query("select * from downloadEpisode where status = :status order by createTime asc")
    List<DownloadEpisode> getByStatus(int status);

    @Query("select * from downloadEpisode where status = 4 and sourceKey = :sourceKey and vodId = :vodId and flag = :flag and episodeIndex = :episodeIndex limit 1")
    DownloadEpisode getCompleted(String sourceKey, String vodId, String flag, int episodeIndex);

    @Query("select * from downloadEpisode where status = 4 and rawUrl = :rawUrl limit 1")
    DownloadEpisode getCompletedByRawUrl(String rawUrl);

    @Query("select * from downloadEpisode where status = 4 and (resolvedUrl = :url or rawUrl = :url) limit 1")
    DownloadEpisode getCompletedByUrl(String url);

    @Query("select count(id) from downloadEpisode where sourceKey = :sourceKey and vodId = :vodId and flag = :flag")
    int countByVod(String sourceKey, String vodId, String flag);

    @Query("delete from downloadEpisode where sourceKey = :sourceKey and vodId = :vodId and flag = :flag")
    int deleteByVod(String sourceKey, String vodId, String flag);

    @Query("delete from downloadEpisode")
    void deleteAll();
}
