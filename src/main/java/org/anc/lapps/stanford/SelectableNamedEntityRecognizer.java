/*-
 * Copyright 2014 The American National Corpus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.anc.lapps.stanford;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreAnnotations.AnswerAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import org.anc.lapps.stanford.util.StanfordUtils;
import org.anc.util.IDGenerator;
import org.lappsgrid.annotations.ServiceMetadata;
import org.lappsgrid.serialization.*;
import org.lappsgrid.serialization.lif.Annotation;
import org.lappsgrid.serialization.lif.Container;
import org.lappsgrid.serialization.lif.View;
import org.lappsgrid.vocabulary.Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.lappsgrid.discriminator.Discriminators.Uri;

@ServiceMetadata(
		  description = "Stanford Named Entity Recognizer (Selectable)",
		  requires = "token",
		  produces = {"date", "person", "location", "organization"}
)
public class SelectableNamedEntityRecognizer extends AbstractStanfordService
{
	private static final Logger logger = LoggerFactory.getLogger(SelectableNamedEntityRecognizer.class);

	private static final String classifierRoot = Constants.PATH.NER_MODEL_ROOT;

	//protected AbstractSequenceClassifier classifier;
	protected Map<String, AbstractSequenceClassifier> pool;
//	protected BlockingQueue<AbstractSequenceClassifier> pool;

	protected Throwable savedException = null;
	protected String exceptionMessage = null;
	protected HashMap<String,String> nameMap = new HashMap<>();

	public static final String ALL = "english.all.3class.distsim.crf.ser.gz";
	public static final String CONLL = "english.conll.4class.distsim.crf.ser.gz";
	public static final String MUC = "english.muc.7class.distsim.crf.ser.gz";
	public static final String NOWIKI = "english.nowiki.3class.distsim.crf.ser.gz";
	public static final String[] NAMES = {
		ALL, CONLL, MUC, NOWIKI
	};

	public SelectableNamedEntityRecognizer()
	{
		super(SelectableNamedEntityRecognizer.class);
		pool = new HashMap<>();
		try
		{
//			String[] names =
//			File root = new File(classifierRoot);
//			for (String name : names)
//			{
//				File file = new File(root, name);
//				pool.put(name, CRFClassifier.getClassifier(file));
//			}

			mapNames(Uri.PERSON, "person", "Person", "PERSON");
			mapNames(Uri.LOCATION, "location", "Location", "LOCATION");
			mapNames(Uri.ORGANIZATION, "org", "organization", "ORGANIZATION");
			mapNames(Uri.DATE, "data", "Date", "DATE");
			logger.info("Stanford Stand-Alone Named-Entity Recognizer created.");
		}

		catch (OutOfMemoryError e)
		{
			logger.error("Ran out of memory creating the CRFClassifier.", e);
			savedException = e;
		}
		catch (Exception e)
		{
			logger.error("Unable to create the CRFClassifier.", e);
			savedException = e;
		}
	}

	private void mapNames(String uri, String... names)
	{
		for (String name : names)
		{
			nameMap.put(name, uri);
		}
	}

	@Override
	public String execute(String input)
	{
		logger.info("Executing the Stanford Named Entity Recognizer.");

		// A savedException indicates there was a problem creating the CRFClassifier
		// object.
		if (savedException != null)
		{
			if (exceptionMessage == null)
			{
				StringWriter stringWriter = new StringWriter();
				PrintWriter writer = new PrintWriter(stringWriter);
				writer.println(savedException.getMessage());
				savedException.printStackTrace(writer);
				exceptionMessage = stringWriter.toString();
			}
			return createError(exceptionMessage);
		}

		Data data = Serializer.parse(input, Data.class);
		if (data == null)
		{
			return createError("Unable to parse input.");
		}
//      String payload = map.get("payload");
//      if (payload == null)
//      {
//         return createError(Messages.MISSING_PAYLOAD);
//      }

		String discriminator = data.getDiscriminator();
		logger.info("Discriminator is {}", discriminator);
		String json = null;
		switch (discriminator)
		{
			case Uri.ERROR:
				json = input;
				break;
			case Uri.GETMETADATA:
				json = super.getMetadata();
				logger.info("Loaded metadata");
				//System.out.println(json);
				break;
			case Uri.LAPPS:   // fall through
			case Uri.JSON:
			case Uri.JSON_LD:
				// Nothing needs to be done other than preventing the default case.
				break;
			default:
				json = createError(Messages.UNSUPPORTED_INPUT_TYPE + discriminator);
				break;
		}

		if (json != null)
		{
			return json;
		}

		Container container = new Container((Map)data.getPayload());
		logger.info("Executing Configurable Stanford Named Entity Recognizer.");

		List<CoreLabel> labels = StanfordUtils.getListOfTaggedCoreLabels(container);

		if (labels == null || labels.size() == 0)
		{
			String message = "Unable to initialize a list of Stanford CoreLabels.";
			logger.warn(message);
			return createError(message);
		}

		Object param = data.getParameter("classifier");
		if (param == null)
		{
//			System.out.println(data.asPrettyJson());
			return createError("No classifier parameter provided.");
		}

		String classifierName = param.toString();
		AbstractSequenceClassifier classifier = getClassifier(classifierName);
		if (classifier == null)
		{
			return createError("No such classifier: " + classifierName);
		}

		List<CoreLabel> classifiedLabels = classifier.classify(labels);

		if (classifiedLabels == null)
		{
			logger.warn("Classifier returned null");
		}
		else if (classifiedLabels.size() == 0)
		{
			logger.warn("No named entities found.");
		}
		else
		{
			logger.info("There are {} labels.", classifiedLabels.size());
			Set<String> types = new HashSet<String>();
			IDGenerator id = new IDGenerator();
			View view = new View();
			String invalidNer = "O";
			for (CoreLabel label : classifiedLabels)
			{
				String ner = label.get(AnswerAnnotation.class);
				logger.info("Label: {}", ner);
				if (!ner.equals(invalidNer))
				{
					Annotation annotation = new Annotation();
					String type = getUriForType(ner);
					types.add(type);
					annotation.setLabel(ner);
					annotation.setAtType(type);
//               annotation.setLabel(correctCase(ner));
					annotation.setId(id.generate("ne"));
					long start = label.beginPosition();
					long end = label.endPosition();
					annotation.setStart(start);
					annotation.setEnd(end);

					Map<String,String> features = annotation.getFeatures();
					add(features, Features.Token.LEMMA, label.lemma());
					add(features, "category", label.category());
					add(features, Features.Token.POS, label.get(CoreAnnotations.PartOfSpeechAnnotation.class));

					add(features, "ner", label.ner());
					add(features, "word", label.word());
					view.addAnnotation(annotation);

				}
			}

			//ProcessingStep step = Converter.addTokens(new ProcessingStep(), labels);
			String producer = this.getClass().getName() + ":" + Version.getVersion();
			for (String type : types)
			{
				logger.info("{} produced by {}", type, producer);
				view.addContains(type, producer, classifierName);
			}
//         view.addContains(Uri.NE, producer, "ner:stanford");
			container.getViews().add(view);
		}
		data.setDiscriminator(Uri.LAPPS);
		data.setPayload(container);

		return data.asJson();
	}

	private AbstractSequenceClassifier getClassifier(String name)
	{
		AbstractSequenceClassifier classifier = pool.get(name);
		if (classifier != null)
		{
			return classifier;
		}

		File file = new File(classifierRoot, name);
		if (!file.exists())
		{
			return null;
		}

		try
		{
			classifier = CRFClassifier.getClassifier(file);
		}
		catch (IOException e)
		{
			return null;
		}
		catch (ClassNotFoundException e)
		{
			return null;
		}
		pool.put(name, classifier);
		return classifier;
	}

	private String getUriForType(String type)
	{
		if ("PERSON".equals(type)) return Uri.PERSON;
		if ("DATE".equals(type)) return Uri.DATE;
		if ("LOCATION".equals(type)) return Uri.LOCATION;
		if ("ORGANIZATION".equals(type)) return Uri.ORGANIZATION;
		if ("MISC".equals(type)) return Uri.NE;
		return type;
	}

	private String correctCase(String item)
	{
//      String head = item.substring(0, 1);
//      String tail = item.substring(1).toLowerCase();
//      return head + tail;
		String uri = nameMap.get(item);
		if (uri == null)
		{
			return item;
		}
		return uri;
	}

	private void add(Map<String,String> features, String name, String value)
	{
		if (value != null)
		{
			features.put(name, value);
		}
	}

//   @Override
//   public Data configure(Data arg0)
//   {
//      return DataFactory.error("Unsupported operation.");
//   }
}
