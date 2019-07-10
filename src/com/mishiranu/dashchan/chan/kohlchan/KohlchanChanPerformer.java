package com.mishiranu.dashchan.chan.kohlchan;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.net.Uri;

import chan.content.ApiException;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.MultipartEntity;
import chan.http.UrlEncodedEntity;
import chan.text.ParseException;
import chan.util.CommonUtils;
import chan.util.StringUtils;

public class KohlchanChanPerformer extends ChanPerformer
{
	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException
	{
		KohlchanChanLocator locator = KohlchanChanLocator.get(this);
		Uri uri = locator.buildPath(data.boardName, (data.isCatalog() ? "catalog"
				: Integer.toString(data.pageNumber + 1 )) + ".json");
		HttpResponse response = new HttpRequest(uri, data.holder, data).setValidator(data.validator).read();
		JSONObject jsonObject = response.getJsonObject();
		JSONArray jsonArray = response.getJsonArray();
		if (jsonObject != null && data.pageNumber >= 0)
		{
			try
			{
				JSONArray threadsArray = jsonObject.getJSONArray("threads");
				Posts[] threads = new Posts[threadsArray.length()];
				for (int i = 0; i < threads.length; i++)
				{
					threads[i] = KohlchanModelMapper.createThread(threadsArray.getJSONObject(i),
							locator,false);
				}
				return new ReadThreadsResult(threads);
			}
			catch (JSONException e)
			{
				throw new InvalidResponseException(e);
			}
		}
		else if (jsonArray != null)
		{
			if (data.isCatalog())
			{
				try
				{
					if (jsonArray.length() == 1)
					{
						jsonObject = jsonArray.getJSONObject(0);
						if (!jsonObject.has("threads")) return null;
					}
					ArrayList<Posts> threads = new ArrayList<>();
					for (int i = 0; i < jsonArray.length(); i++)
					{
						threads.add(KohlchanModelMapper.createThread(jsonArray.getJSONObject(i),
								locator, true));
					}
					return new ReadThreadsResult(threads);
				}
				catch (JSONException e)
				{
					throw new InvalidResponseException(e);
				}
			}
			else if (jsonArray.length() == 0) return null;
		}
		throw new InvalidResponseException();
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException
	{
		KohlchanChanLocator locator = KohlchanChanLocator.get(this);
		Uri uri = locator.buildPath(data.boardName, "res", data.threadNumber + ".json");
		JSONObject jsonObject = new HttpRequest(uri, data.holder, data).setValidator(data.validator)
				.read().getJsonObject();
		if (jsonObject != null)
		{
			try
			{
				return new ReadPostsResult(KohlchanModelMapper.createThread(jsonObject, locator, false));
			}
			catch (JSONException e)
			{
				throw new InvalidResponseException(e);
			}
		}
		throw new InvalidResponseException();
	}

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException
	{
		KohlchanChanLocator locator = KohlchanChanLocator.get(this);
		Uri uri = locator.buildPath(".static/pages", "sidebar.html");
		String responseText = new HttpRequest(uri, data.holder, data).read().getString();
		try
		{
			return new ReadBoardsResult(new KohlchanBoardsParser(responseText).convert());
		}
		catch (ParseException e)
		{
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadPostsCountResult onReadPostsCount(ReadPostsCountData data) throws HttpException, InvalidResponseException
	{
		KohlchanChanLocator locator = KohlchanChanLocator.get(this);
		Uri uri = locator.buildPath(data.boardName, "res", data.threadNumber + ".json");
		JSONObject jsonObject = new HttpRequest(uri, data.holder, data).setValidator(data.validator)
				.read().getJsonObject();
		if (jsonObject != null)
		{
			try
			{
				return new ReadPostsCountResult(jsonObject.getJSONArray("posts").length());
			}
			catch (JSONException e)
			{
				throw new InvalidResponseException(e);
			}
		}
		throw new InvalidResponseException();
	}

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException, InvalidResponseException
	{
		MultipartEntity entity = new MultipartEntity();
		entity.add("boardUri", data.boardName);
		entity.add("threadId", data.threadNumber);
		entity.add("name", data.name);
		entity.add("subject", data.subject);
		entity.add("message", StringUtils.emptyIfNull(data.comment));
		entity.add("password", data.password);
		if(data.optionSage) entity.add("sage", "true");
		if(data.optionSpoiler) entity.add("spoiler", "true");

		if (data.attachments != null)
		{

			for (int i = 0; i < data.attachments.length; i++)
			{
				SendPostData.Attachment attachment = data.attachments[i];
				entity.add("fileName", attachment.getFileName());
				entity.add("fileMime", attachment.getMimeType());
				attachment.addToEntity(entity, "files");
			}
		}

		KohlchanChanLocator locator = KohlchanChanLocator.get(this);
		Uri contentUri = data.threadNumber != null ? locator.createThreadUri(data.boardName, data.threadNumber)
				: locator.createBoardUri(data.boardName, 0);
		Uri uri = locator.buildPath(data.threadNumber != null ? "/replyThread.js?json=1" : "/newThread.js?json=1");
		JSONObject jsonObject = new HttpRequest(uri, data.holder, data).setPostMethod(entity).addHeader("Referer",
				contentUri.toString()).setRedirectHandler(HttpRequest.RedirectHandler.STRICT).read().getJsonObject();
		if (jsonObject == null) throw new InvalidResponseException();

		String status = jsonObject.optString("status");
		String response = jsonObject.optString("data");
		if ("ok".equals(status)) {
			String postNumber = response;
			if (postNumber.isEmpty()) throw new InvalidResponseException();
			String threadNumber = data.threadNumber != null ? data.threadNumber : postNumber;
			return new SendPostResult(threadNumber, postNumber);
		} else if("bypassable".equals(status)) {
			throw new ApiException(ApiException.SEND_ERROR_CAPTCHA);
		} else if(!"error".equals(status)) {
			CommonUtils.writeLog("Unrecognised post reply status: ", status);
			throw new InvalidResponseException();
		}

		int errorType = 0;
		if (response.contains("Either a message or a file is required"))
			errorType = ApiException.SEND_ERROR_EMPTY_COMMENT;
		else if (response.contains("This board requires at least one file when creating threads."))
			errorType = ApiException.SEND_ERROR_EMPTY_FILE;
		else if (response.contains("This board is locked"))
			errorType = ApiException.SEND_ERROR_CLOSED;
		else if (response.contains("Board not found"))
			errorType = ApiException.SEND_ERROR_NO_BOARD;
		else if (response.contains("Thread not found"))
			errorType = ApiException.SEND_ERROR_NO_THREAD;
		else if (response.contains("A file had a format that is not allowed"))
			errorType = ApiException.SEND_ERROR_FILE_NOT_SUPPORTED;
		else if (response.contains("A file sent was too large"))
			errorType = ApiException.SEND_ERROR_FILE_TOO_BIG;
		else if (response.contains("Banned file"))
			errorType = ApiException.SEND_ERROR_FILE_EXISTS;
		else if (response.contains("banned"))
			errorType = ApiException.SEND_ERROR_BANNED;
		else if (response.contains("Flood detected"))
			errorType = ApiException.SEND_ERROR_TOO_FAST;
		else
		{
			CommonUtils.writeLog("Unknown post error received: ", response);
			throw new ApiException(response);
		}

		throw new ApiException(errorType);
	}

	@Override
	public SendDeletePostsResult onSendDeletePosts(SendDeletePostsData data) throws HttpException, ApiException,
			InvalidResponseException
	{
		KohlchanChanLocator locator = KohlchanChanLocator.get(this);
		MultipartEntity entity = new MultipartEntity();
		entity.add("password", data.password);
		entity.add("action", "delete");
		for (String postNumber : data.postNumbers)
			entity.add(data.boardName + "-" + data.threadNumber + "-" + postNumber, "true");
		if (data.optionFilesOnly)
		{
			// "deleteMedia" times out (too expensive?)
			entity.add("deleteUploads", "true");
		}
		Uri uri = locator.buildPath("/contentActions.js?json=1");
		JSONObject jsonObject = new HttpRequest(uri, data.holder, data).setPostMethod(entity)
				.setRedirectHandler(HttpRequest.RedirectHandler.STRICT).read().getJsonObject();

		String status = jsonObject.optString("status");
		if ("ok".equals(status)) {
			JSONObject response = jsonObject.optJSONObject("data");
			if (response != null && response.optInt("removedThreads") == 0
					&& response.optInt("removedPosts") == 0)
				throw new ApiException(ApiException.DELETE_ERROR_PASSWORD); // can't tell why it failed

			return null;
		} else if(!"error".equals(status)) {
			CommonUtils.writeLog("Unrecognised post deletion status: ", status);
			throw new InvalidResponseException();
		}

		String response = jsonObject.optString("data");
		CommonUtils.writeLog("Delete post failed: ", response);
		throw new ApiException(response);
	}

	//! This is currently broken. It relies on a CAPTCHA, but DashChan doesn't support that for reports.
	@Override
	public SendReportPostsResult onSendReportPosts(SendReportPostsData data) throws HttpException, ApiException,
			InvalidResponseException
	{
		KohlchanChanLocator locator = KohlchanChanLocator.get(this);
		MultipartEntity entity = new MultipartEntity();
		entity.add("action", "report");
		// TODO: entity.add("captcha", captchaValue);
		entity.add("reason", data.comment);
		for (String postNumber : data.postNumbers)
			entity.add(data.boardName + "-" + data.threadNumber + "-" + postNumber, "true");
		Uri uri = locator.buildPath("/contentActions.js?json=1");
		JSONObject jsonObject = new HttpRequest(uri, data.holder, data).setPostMethod(entity)
				.setRedirectHandler(HttpRequest.RedirectHandler.STRICT).read().getJsonObject();

		String status = jsonObject.optString("status");
		if ("ok".equals(status)) {
			return null;
		} else if (!"error".equals(status)) {
			CommonUtils.writeLog("Unrecognised post reporting status: ", status);
			throw new InvalidResponseException();
		}

		String response = jsonObject.optString("data");
		CommonUtils.writeLog("Report post failed: ", response);
		if(response.contains("Wrong captcha"))
			throw new ApiException(ApiException.SEND_ERROR_CAPTCHA); // XXX: Report, not send error
		else
			throw new ApiException(response);
	}
}
