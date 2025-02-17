package ru.hh.vsplitter.split;

import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import ru.hh.vsplitter.classify.Classifier;
import ru.hh.vsplitter.classify.ClassifierException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.filter;
import static ru.hh.vsplitter.split.VacancyBlock.CONDITIONS;
import static ru.hh.vsplitter.split.VacancyBlock.REQUIREMENTS;
import static ru.hh.vsplitter.split.VacancyBlock.RESPONSIBILITIES;

@SuppressWarnings("unused")
public class VacancySplitter  {
  private static final float THRESHOLD = 0.1f;

  private enum Language {
    ENGLISH("abcdefghijklmnopqrstuvwxyz", Arrays.asList(REQUIREMENTS, RESPONSIBILITIES)),
    RUSSIAN("абвгдеёжзийклмнопрстуфхцчшщъыьэюя", Arrays.asList(REQUIREMENTS, RESPONSIBILITIES, CONDITIONS));

    private final CharMatcher matcher;
    private final Set<VacancyBlock> mandatoryBlocks;

    Language(String alphabet, Collection<VacancyBlock> mandatoryBlocks) {
      matcher = CharMatcher.anyOf(alphabet);
      this.mandatoryBlocks = Set.copyOf(mandatoryBlocks);
    }

    int countIn(String text) {
      return matcher.countIn(text);
    }
  }

  private final Logger logger = LoggerFactory.getLogger(VacancySplitter.class);

  private final SAXParserFactory parserFactory = SAXParserFactory.newInstance();

  private final XmlErrorHandler errorHandler = new XmlErrorHandler(logger);
  private final TextHandler fetchTextHandler = new TextHandler();

  private final HtmlBlocksHandler ulHandler = new UlHandler();
  private final HtmlBlocksHandler sentenceHandler = new SentenceHandler();

  private final Map<String, VacancyBlock> classToBlock;
  private final Map<Language, Classifier> classifiers;

  public VacancySplitter(Classifier engClassifier, Classifier rusClassifier, Map<String, VacancyBlock> classToBlock) {
    classifiers = Map.of(Language.ENGLISH, engClassifier, Language.RUSSIAN, rusClassifier);
    this.classToBlock = classToBlock;
  }

  private static final Function<Collection<?>, Integer> SIZE_FUNCTION = new Function<Collection<?>, Integer>() {
    @Override
    public Integer apply(Collection<?> input) {
      return input.size();
    }
  };

  public Map<VacancyBlock, String> split(String text) throws SplitterException {
    Language language = inferLanguage(text);
    Classifier classifier = classifiers.get(language);

    Map<VacancyBlock, List<List<String>>> sequentialBlocks = parseIntoBlocks(text, ulHandler, classifier);

    if (sequentialBlocks.keySet().containsAll(language.mandatoryBlocks)) {
      Map<VacancyBlock, String> concatenated = new HashMap<>();
      for (Map.Entry<VacancyBlock, List<List<String>>> entry : sequentialBlocks.entrySet()) {
        concatenated.put(entry.getKey(), Joiner.on(' ').join(concat(entry.getValue())));
      }
      return concatenated;
    }

    sequentialBlocks = parseIntoBlocks(text, sentenceHandler, classifier);

    Map<VacancyBlock, String> concatenated = new HashMap<>();
    for (VacancyBlock block : sequentialBlocks.keySet()) {
      List<List<String>> sequence = sequentialBlocks.get(block);
      int maxSize = Ordering.natural().max(Iterables.transform(sequence, SIZE_FUNCTION));
      final int threshold = (int) (maxSize * THRESHOLD);
      concatenated.put(block, Joiner.on(' ').join(concat(filter(sequence, new Predicate<List<String>>() {
        @Override
        public boolean apply(List<String> input) {
          return input.size() > threshold;
        }
      }))));
    }
    return concatenated;
  }

  private Map<VacancyBlock, List<List<String>>> parseIntoBlocks(String xml, HtmlBlocksHandler handler,
                                                                Classifier classifier) throws SplitterException {
    try (ByteArrayInputStream bos = new ByteArrayInputStream(xml.getBytes())) {
      XMLReader reader = newReader(handler);
      reader.parse(new InputSource(bos));
      return markSequentialBlocks(handler.getTextBlocks(), classifier);
    } catch (IOException | SAXException | ClassifierException e) {
      throw new SplitterException(e);
    }
  }

  private VacancyBlock classifyBlock(String current, String leftContext, String rightContext,
                                     Classifier classifier) throws ClassifierException {
    final VacancyBlock quorum;

    var leftBlock = classifyBlock(classifier, leftContext + current);
    var currentBlock = classifyBlock(classifier, current);
    if (leftBlock == currentBlock) {
      quorum = leftBlock;
    } else {
      var rightBlock = classifyBlock(classifier, rightContext + current);
      if (rightBlock == leftBlock) {
        quorum = rightBlock;
      } else {
        quorum = currentBlock;
      }
    }

    return quorum;
  }

  private VacancyBlock classifyBlock(Classifier classifier, String text) throws ClassifierException {
    var blockClass = classifier.classify(text);
    return blockClass == null ? null : classToBlock.get(blockClass);
  }

  private Map<VacancyBlock, List<List<String>>> markSequentialBlocks(List<String> textBlocks,
                                                                     Classifier classifier) throws ClassifierException {
    ListMultimap<VacancyBlock, List<String>> result = ArrayListMultimap.create();
    VacancyBlock previousClass = null;
    List<String> currentSequence = null;

    for (int blockIndex = 0; blockIndex < textBlocks.size(); blockIndex++) {

      String current = textBlocks.get(blockIndex);

      String leftContext = blockIndex > 0 ? textBlocks.get(blockIndex - 1) : "";
      String rightContext = blockIndex < textBlocks.size() - 1 ? textBlocks.get(blockIndex + 1) : "";
      VacancyBlock currentClass = classifyBlock(current, leftContext, rightContext, classifier);

      if (currentClass != null) {
        if (previousClass != currentClass) {
          currentSequence = new ArrayList<>();
          result.get(currentClass).add(currentSequence);
        }
        currentSequence.add(current);
      }

      previousClass = currentClass;
    }

    // correctness of conversion is guaranteed by ArrayListMultimap implementation
    return Maps.transformValues(result.asMap(), new Function<Collection<List<String>>, List<List<String>>>() {
      @Override
      public List<List<String>> apply(Collection<List<String>> input) {
        return (List<List<String>>) input;
      }
    });
  }

  private Language inferLanguage(String text) throws SplitterException {
    try(ByteArrayInputStream bos = new ByteArrayInputStream(text.getBytes())) {
      newReader(fetchTextHandler).parse(new InputSource(bos));
      String lowered = fetchTextHandler.getText().toLowerCase();
      return (Language.ENGLISH.countIn(lowered) > Language.RUSSIAN.countIn(lowered)) ?
          Language.ENGLISH :
          Language.RUSSIAN;
    } catch (IOException | SAXException e) {
      throw new SplitterException(e);
    }
  }

  private XMLReader newReader(ContentHandler contentHandler) throws SplitterException {
    try {
      XMLReader reader = parserFactory.newSAXParser().getXMLReader();
      reader.setErrorHandler(errorHandler);
      reader.setContentHandler(contentHandler);
      return reader;
    } catch (ParserConfigurationException | SAXException e) {
      throw new SplitterException(e);
    }
  }
}
