package fr.pilato.elasticsearch.crawler.fs.tika.customparser;

import java.io.IOException;
import java.io.InputStream;

public class MicrosoftVisionParser implements CustomOCRParser {

	@Override
	public OCRResponseModel extractText(InputStream inputStream, String mocrosoftOcpApimSubscriptionKey) throws IOException, InterruptedException {
		return MicrosoftVisionClient.getResponse(inputStream, mocrosoftOcpApimSubscriptionKey);
	}

}