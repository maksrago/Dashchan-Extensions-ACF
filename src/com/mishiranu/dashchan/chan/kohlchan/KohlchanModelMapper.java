package com.mishiranu.dashchan.chan.kohlchan;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import chan.content.model.FileAttachment;
import chan.content.model.Icon;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.util.CommonUtils;
import chan.util.StringUtils;

public class KohlchanModelMapper
{
	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
	private static final Pattern PATTERN_EXTENDED_ISO8601 = Pattern.compile("(....-..-..T..:..:..)(.\\d+)?(Z|\\+..:..)");

	private static long iso8601ToEpochMs(String timestamp) throws JSONException
	{
		Matcher matcher = PATTERN_EXTENDED_ISO8601.matcher(timestamp);
		if (!matcher.matches())
			throw new JSONException("invalid date: " + timestamp);

		try
		{
			return TIME_FORMAT.parse(
					matcher.group(1) + matcher.group(3).replace("Z", "+00:00")
							.replace(":", "")
			).getTime();
		}
		catch (ParseException e)
		{
			throw new JSONException("invalid date: " + timestamp);
		}
	}

	public static FileAttachment createFileAttachment(JSONObject jsonObject, KohlchanChanLocator locator) throws JSONException
	{
		FileAttachment attachment = new FileAttachment();
		String originalName = CommonUtils.getJsonString(jsonObject, "originalName");
		String thumbPath = CommonUtils.getJsonString(jsonObject, "thumb");
		String path = CommonUtils.getJsonString(jsonObject, "path");

		// fix up file extension so that Dashchan correctly derives the MIME type
		if (path.length() >= 5 && path.charAt(path.length() - 4) != '.'
				&& path.charAt(path.length() - 5) != '.')
			path = path + "/" + originalName;

		attachment.setThumbnailUri(locator, locator.buildPath(thumbPath));
		attachment.setFileUri(locator, locator.buildPath(path));
		attachment.setOriginalName(originalName);
		attachment.setSize(jsonObject.optInt("size"));
		attachment.setWidth(jsonObject.optInt("width"));
		attachment.setHeight(jsonObject.optInt("height"));
		return attachment;
	}

	public static Post createPost(JSONObject jsonObject, KohlchanChanLocator locator)
			throws JSONException
	{
		Post post = new Post();
		post.setSticky(jsonObject.optBoolean("pinned"));
		post.setClosed(jsonObject.optBoolean("locked"));
		post.setCyclical(jsonObject.optBoolean("cyclic"));
		post.setArchived(jsonObject.optBoolean("archived"));
		post.setPosterBanned(jsonObject.has("banMessage"));
		post.setPostNumber(CommonUtils.optJsonString(jsonObject, "postId",
				CommonUtils.optJsonString(jsonObject, "threadId")));

		String timestamp =  CommonUtils.optJsonString(jsonObject, "creation");
		if(!StringUtils.isEmpty(timestamp))
			post.setTimestamp(iso8601ToEpochMs(timestamp));

		String name = CommonUtils.optJsonString(jsonObject, "name");
		if (name != null)
		{
			name = StringUtils.nullIfEmpty(StringUtils.clearHtml(name).trim());
			post.setName(name);
		}
		post.setTripcode(CommonUtils.optJsonString(jsonObject, "trip"));
		post.setIdentifier(CommonUtils.optJsonString(jsonObject, "id"));
		post.setCapcode(CommonUtils.optJsonString(jsonObject, "signedRole"));
		String email = CommonUtils.optJsonString(jsonObject, "email");
		if (!StringUtils.isEmpty(email))
		{
			if (email.equalsIgnoreCase("sage"))
				post.setSage(true);
			else
				post.setEmail(email);
		}
		if (jsonObject.optBoolean("autoSage")) // OP only
		{
			post.setSage(true); // ignored by DashChan
			post.setCapcode((post.getCapcode() != null ? post.getCapcode() + " " : "") + "(autosäge)");
		}


		String flag = CommonUtils.optJsonString(jsonObject, "flag");
		CommonUtils.writeLog("flag: ", flag);
		if (!StringUtils.isEmpty(flag))
			post.setIcons(new Icon(locator, locator.buildPath(flag),
					jsonObject.optString("flagName")));


		String sub = CommonUtils.optJsonString(jsonObject, "subject");
		post.setSubject(StringUtils.nullIfEmpty(StringUtils.clearHtml(sub).trim()));

		post.setComment(CommonUtils.optJsonString(jsonObject, "markdown"));

		JSONArray filesArray = jsonObject.optJSONArray("files");
		if (filesArray != null)
		{
			FileAttachment[] attachments = new FileAttachment[filesArray.length()];
			for (int i = 0; i < filesArray.length(); i++)
			{
				JSONObject fileObject = filesArray.getJSONObject(i);
				attachments[i] = createFileAttachment(fileObject, locator);
			}
			post.setAttachments(attachments);
		}

		return post;
	}

	public static Posts createThread(JSONObject jsonObject, KohlchanChanLocator locator,
									 boolean fromCatalog) throws JSONException
	{
		Post[] posts;
		int postsCount = 0;
		int filesCount = 0;
		if (fromCatalog)
		{
			Post post = createPost(jsonObject, locator);
			post.setThreadNumber(post.getPostNumber());
			//TODO: Set thumb as icon
			postsCount = jsonObject.optInt("postCount");
			filesCount = jsonObject.optInt("fileCount");

			posts = new Post[] {post};
		}
		else
		{
			JSONArray jsonArray = jsonObject.getJSONArray("posts");
			posts = new Post[jsonArray.length() + 1];

			posts[0] = createPost(jsonObject, locator);
			posts[0].setThreadNumber(posts[0].getPostNumber());
			postsCount = posts.length + jsonObject.optInt("ommitedPosts" /*sic*/, 0);
			filesCount = 0; // missing :<
			filesCount += posts[0].getAttachmentsCount();

			for (int i = 0; i < posts.length - 1; i++)
			{
				posts[i + 1] = createPost(jsonArray.getJSONObject(i), locator);
				posts[i + 1].setThreadNumber(posts[0].getThreadNumber());
				posts[i + 1].setParentPostNumber(posts[0].getPostNumber());
			}
		}
		return new Posts(posts).addPostsCount(postsCount).addFilesCount(filesCount);
	}
}
