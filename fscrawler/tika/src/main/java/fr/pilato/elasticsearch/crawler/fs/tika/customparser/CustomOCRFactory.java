package fr.pilato.elasticsearch.crawler.fs.tika.customparser;

import javax.xml.bind.ValidationException;

public class CustomOCRFactory {

	public static CustomOCRParser getCustomOCR(String ocrProviderName) throws ValidationException {
		
		if (ocrProviderName == null) {
			throw new ValidationException("OCR Provider name should not be null. ex : Microsoft");
		}
		
		switch (ocrProviderName) {
		case "Microsoft":
			return new MicrosoftVisionParser();
			//we can go for any new features here
		default:
			throw new ValidationException("Please provide OCR provider name correctly. ex : Microsoft");
		}
		
	}
}
