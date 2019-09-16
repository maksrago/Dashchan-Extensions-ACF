package com.sixsixthree.dashchan.chan.spacechan;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.text.GroupParser;
import chan.text.ParseException;
import chan.util.StringUtils;

public class SpacechanBoardsParser implements GroupParser.Callback {
	private final String mSource;

	private final ArrayList<BoardCategory> mBoardCategories = new ArrayList<>();
	private final ArrayList<Board> mBoards = new ArrayList<>();

	private enum ParserState {
		kSearchingForHeadline,
		kSearchingForCategory,
		kReadingCategoryName,
		kSearchingForBoardUrl,
		kReadingBoardTitle
	}

	;

	private ParserState mParserState = ParserState.kSearchingForCategory;
	private String mCategoryName = "";
	private String mBoardUrl = "";

	private static final Pattern PATTERN_BOARD_URI = Pattern.compile("/(.*)/");
	private static final Pattern PATTERN_BOARD_TITLE = Pattern.compile("/\\w+/ - (.*)");

	public SpacechanBoardsParser(String source) {
		mSource = source;
	}

	public ArrayList<BoardCategory> convert() throws ParseException {
		try {
			mParserState = ParserState.kSearchingForHeadline;
			GroupParser.parse(mSource, this);
		} catch (FinishedException e) {

		}
		return mBoardCategories;
	}

	private static class FinishedException extends ParseException {
		private static final long serialVersionUID = 1L;
	}

	@Override
	public boolean onStartElement(GroupParser parser, String tagName, String attrs) {
		switch (mParserState) {
			case kSearchingForHeadline:
			case kSearchingForCategory:
				if ("h2".equals(tagName)) {
					mParserState = ParserState.kReadingCategoryName;
				} else if ("ul".equals(tagName)) {
					String cssClass = parser.getAttr(attrs, "class");
					if ("boardlist".equals(cssClass))
						mParserState = ParserState.kSearchingForBoardUrl;
				}
				break;
			case kSearchingForBoardUrl:
				if ("a".equals(tagName)) {
					Matcher matcher = PATTERN_BOARD_URI.matcher(parser.getAttr(attrs, "href"));
					if (matcher.matches()) {
						mBoardUrl = matcher.group(1);
						mParserState = ParserState.kReadingBoardTitle;
					}
				}
				break;

		}
		return false;
	}

	@Override
	public void onEndElement(GroupParser parser, String tagName) throws FinishedException {
		if (mParserState == ParserState.kSearchingForBoardUrl && "ul".equals(tagName)) {
			ArrayList<Board> boards = mBoards;
			if (boards.size() > 0) {
				mBoardCategories.add(new BoardCategory(mCategoryName, boards));
				mBoards.clear();
				mCategoryName = "";
			}

			mParserState = ParserState.kSearchingForHeadline;
		}
	}

	@Override
	public void onText(GroupParser parser, String source, int start, int end) {
		switch (mParserState) {
			case kReadingCategoryName:
				mCategoryName = StringUtils.clearHtml(source.substring(start, end)).trim();
				mParserState = ParserState.kSearchingForCategory;
				break;
			case kReadingBoardTitle:
				String boardTitle = source.substring(start, end);
				Matcher matcher = PATTERN_BOARD_TITLE.matcher(boardTitle);
				if (matcher.matches())
					boardTitle = matcher.group(1);

				mBoards.add(new Board(mBoardUrl, StringUtils.clearHtml(boardTitle).trim()));
				mBoardUrl = "";
				mParserState = ParserState.kSearchingForBoardUrl;
				break;
		}
	}

	@Override
	public void onGroupComplete(GroupParser parser, String text) {

	}
}
