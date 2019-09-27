package fr.pilato.elasticsearch.crawler.fs.tika.customparser;

import java.io.IOException;
import java.io.InputStream;

public interface CustomOCRParser {
	
	OCRResponseModel extractText(InputStream inputStream, String mocrosoftOcpApimSubscriptionKey) throws IOException, InterruptedException;
}
