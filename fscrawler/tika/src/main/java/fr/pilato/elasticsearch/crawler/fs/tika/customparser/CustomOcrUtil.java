package fr.pilato.elasticsearch.crawler.fs.tika.customparser;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;

public class CustomOcrUtil {
	/**
	 * To extract original content from the extracted json with metadata.
	 * @param finalResponse
	 * @return StringBuilder with original extracted content from the document
	 */
	public static StringBuilder extractOriginalContentFromFinalJsonResponse(String finalResponse) {

		StringBuilder builder = new StringBuilder();
		JSONObject jsonResponse = new JSONObject(finalResponse);
		JSONArray pages = (JSONArray) jsonResponse.get("recognitionResults");
		int r = 0;
		for (int i = 0; i < pages.length(); i++) {
			JSONObject page = (JSONObject) pages.get(i);
			if(r!=0) {
			builder.append("\n\n");
			}
			r++;
			JSONArray lines = (JSONArray) page.get("lines");
			for (int j = 0; j < lines.length(); j++) {
				JSONObject line = (JSONObject) lines.get(j);
				builder.append("\n" + line.get("text"));
			}
		}
		return builder;
	}
}
