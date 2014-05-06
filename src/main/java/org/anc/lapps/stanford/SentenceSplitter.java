package org.anc.lapps.stanford;

import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;
import org.anc.lapps.serialization.Container;
import org.anc.lapps.serialization.ProcessingStep;
import org.anc.lapps.stanford.util.Converter;
import org.lappsgrid.api.Data;
import org.lappsgrid.core.DataFactory;
import org.lappsgrid.discriminator.DiscriminatorRegistry;
import org.lappsgrid.discriminator.Types;
import org.lappsgrid.vocabulary.Annotations;
import org.lappsgrid.vocabulary.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author Keith Suderman
 */
public class SentenceSplitter extends AbstractStanfordService
{
   private static final Logger logger = LoggerFactory.getLogger(SentenceSplitter.class);

   public static final long DELAY = 5;
   public static final TimeUnit UNIT = TimeUnit.SECONDS;

   public SentenceSplitter()
   {
      super("tokenize, ssplit");
      logger.info("Standford sentence splitter created.");
   }

   @Override
   public Data execute(Data input)
   {
      logger.info("Executing Stanford sentence splitter.");
      Container container;
      long type = input.getDiscriminator();
      if (type == Types.TEXT)
      {
         container = new Container(false);
         container.setText(input.getPayload());
      }
      else if (type == Types.JSON)
      {
         container = new Container(input.getPayload());
      }
      else
      {
         String name = DiscriminatorRegistry.get(type);
         String message = "Invalid input type. Expected TEXT or JSON but found " + name;
         logger.warn(message);
         return DataFactory.error(message);
      }

      Annotation document = new Annotation(container.getText());
      Data data = null;
      StanfordCoreNLP service = null;
      try
      {
         //service = pool.take();
         service = pool.poll(DELAY, UNIT);
         if (service == null) {
            logger.warn("The SentenceSplitter was unable to respond to a request in a timely fashion.");
            return DataFactory.error(Messages.BUSY);
         }

         service.annotate(document);
         List<CoreMap> sentences = document.get(SentencesAnnotation.class);
         ProcessingStep step = Converter.addSentences(new ProcessingStep(), sentences);
         String producer = this.getClass().getName() + ":" + Version.getVersion();
         step.addContains(Annotations.TOKEN, producer, "tokenization:stanford");
         step.addContains(Annotations.SENTENCE, producer, "chunk:sentence");
         container.getSteps().add(step);
         data = DataFactory.json(container.toJson());
      }
      catch (InterruptedException e)
      {
         data = DataFactory.error(e.getMessage());
      }
      finally
      {
         if (service != null)
         {
            pool.add(service);
         }
      }
//      String stringList = LappsUtils.makeStringList(list);
      logger.info("Sentence splitter complete.");
      return data;
   }


   @Override
   public long[] requires()
   {
      return new long[] { Types.TEXT };
   }

   @Override
   public long[] produces()
   {
      return new long[] { Types.JSON, Types.SENTENCE, Types.TOKEN };
   }
}
