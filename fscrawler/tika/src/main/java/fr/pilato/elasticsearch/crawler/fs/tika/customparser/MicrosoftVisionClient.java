package fr.pilato.elasticsearch.crawler.fs.tika.customparser;

import java.io.IOException;
import java.io.InputStream;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.openjson.JSONObject;

public class MicrosoftVisionClient {

	private final static Logger logger = LogManager.getLogger(MicrosoftVisionClient.class);

	/**
	 * Hitting Microsoft Batch Read file API - Computer Vision API - v2.0 to extract
	 * text from file (images and documents) Can handle hand-written, printed or
	 * mixed documents
	 * 
	 * @param inputStream
	 * @param mocrosoftOcpApimSubscriptionKey
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static OCRResponseModel getResponse(InputStream inputStream, String mocrosoftOcpApimSubscriptionKey)
			throws IOException, InterruptedException {

		OCRResponseModel ocrResponse = new OCRResponseModel();

		try {
			HttpClient client = HttpClientBuilder.create().build();
			HttpPost asyncBatchAnalyzeRequest = new HttpPost(
					"https://brs.cognitiveservices.azure.com//vision/v2.0/read/core/asyncBatchAnalyze");
			asyncBatchAnalyzeRequest.setHeader("ocp-apim-subscription-key", mocrosoftOcpApimSubscriptionKey);
			asyncBatchAnalyzeRequest.setHeader("content-type", "application/octet-stream");
			asyncBatchAnalyzeRequest.setEntity(new InputStreamEntity(inputStream));

			HttpResponse asyncBatchAnalyzeResponse = client.execute(asyncBatchAnalyzeRequest);

			logger.trace("Batch Read File interface API response : " + asyncBatchAnalyzeResponse);

			/**
			 * Response 202 - Request accepted When you use the Batch Read File interface,
			 * the response contains a field called "Operation-Location". The
			 * "Operation-Location" field contains the URL that you must use for your Get
			 * Read Operation Result operation to access OCR results.
			 * https://azure.microsoft.com/en-us/try/cognitive-services/​
			 */
			if (asyncBatchAnalyzeResponse.getStatusLine().getStatusCode() == 202) {
				boolean isStillRunning = true;
				// processing time will depends on the size/capacity of the file. the loop will
				// rotate until processing completes based on status
				while (isStillRunning) {
					// to read operation using Operation-Location URL present in the header of the
					// previous response
					HttpGet operationLocationURLRequest = new HttpGet(
							asyncBatchAnalyzeResponse.getFirstHeader("Operation-Location").getValue());
					operationLocationURLRequest.setHeader("ocp-apim-subscription-key", mocrosoftOcpApimSubscriptionKey);
					operationLocationURLRequest.setHeader("content-type", "application/json");
					HttpResponse finalResponseFromMicrosoftOCR = client.execute(operationLocationURLRequest);
					String response = EntityUtils.toString(finalResponseFromMicrosoftOCR.getEntity());
					JSONObject jsonResponse = new JSONObject(response);
					// check still processing ?
					isStillRunning = jsonResponse.get("status") != null && jsonResponse.get("status").equals("Running");
					if (!isStillRunning) {
						StringBuilder originalContent = CustomOcrUtil.extractOriginalContentFromFinalJsonResponse(response);
						ocrResponse.setExtractedTextResponse(originalContent.toString());
						ocrResponse.setExtractedTextResponseWithMetaData(response);
						break;
					}
					// let the thread sleep for 50ms
					Thread.sleep(3000);
				}
			} else {
				/**
				 * Custom OCR - Microsoft Computer Vision API - v2.0 - failure cases error
				 * codes- 400, 500, 503 -> BadArgument, InvalidImageURL, FailedToDownloadImage,
				 * InvalidImage, UnsupportedImageFormat InvalidImageSize, InvalidImageDimension
				 */
				ocrResponse.setResponseCode(asyncBatchAnalyzeResponse.getStatusLine().getStatusCode());
				ocrResponse.setStatusMessage(asyncBatchAnalyzeResponse.getStatusLine().getReasonPhrase());
				// if custom OCR fails, redirect to default one
				ocrResponse.setUseDefaultParser(true);
			}
		} catch (Exception e) {
			logger.debug("Exception occured while working with Microsoft Computer Vision API ocr parser.", e);
			logger.trace("The extraction process will be continuing with the default parser...");
			ocrResponse.setUseDefaultParser(true);
		}

		return ocrResponse;
	}
}
