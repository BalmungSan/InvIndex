package goofle.invindex

//spark imports
import org.apache.spark.sql.SparkSession

//mongo imports
import org.mongodb.scala.bson.Document
import com.mongodb.spark.MongoSpark

/** ==InvIndex==
 * ===Spark application to create the inverted index of a group of files===
 *
 * This program opens all files in the path specified in the first argument
 * then computes the inverted index table for those files and finally
 * saves the table in the mongo's collection specified by the property
 * 'spark.mongodb.output.uri'
 *
 * The created index is of the form
 * {{{ '{word: "word", files: [{file: "file", count: "count"}]}' }}}
 *
 * '''where:'''
 - '''word:''' is a word that appeared in the source files
 * in its inflected form.
 - '''file:''' is the filename of a document where the word appeared
 * in the form '/folder/file'
 - '''count:''' is the number of times the word appeared in that document
 *
 * @note the files array is sorted in decreasing order,
 * where the comparison criterion is the number of times
 * the word appeared in that document
 *
 * @author Luis Miguel Mejía Suárez (BalmungSan) 
 * @version 1.0.0
 */
object InvIndex {
  /** Count all words in a text
   * @param text the text to analyse
   * @return a [[scala.collection.Seq Seq]] of tuples (word, count)
   * @note this method runs in parallel 
   */
  private def countWords(text: String): Seq[(String, Int)] = {
    //checks if a word is valid
    def isValidWord(word: String): Boolean = {
      word != ""
    }

    //returns the inflected form of a word
    def toInflectedForm(word: String): String = {
      word.toLowerCase map {
        case 'á' => 'a'
        case 'à' => 'a'
        case 'â' => 'a'
        case 'ä' => 'a'
        case 'é' => 'e'
        case 'è' => 'e'
        case 'ê' => 'e'
        case 'ë' => 'e'
        case 'í' => 'i'
        case 'ì' => 'i'
        case 'î' => 'i'
        case 'ï' => 'i'
        case 'ó' => 'o'
        case 'ò' => 'o'
        case 'ô' => 'o'
        case 'ö' => 'o'
        case 'ú' => 'u'
        case 'ù' => 'u'
        case 'û' => 'u'
        case 'ü' => 'u' 
        case c   => c
      }
    }

    //gets all words in the text in parallel
    val words = for {
      w <- text.split("[^A-Za-z]").par //splits by every character that isn't a letter
      if isValidWord(w)
    } yield toInflectedForm(w)

    //counts the words
    val counts = words groupBy (w => w) mapValues (_.size)
    counts.seq.toSeq
  }

  /** ===Main method===
   * All code logic runs here
   * @param args command line arguments
   */
  def main(args: Array[String]): Unit = {
    //start the application
    val spark = SparkSession.builder()
      .appName("InvertedIndex")
      .getOrCreate()
    val sc = spark.sparkContext

    //open all files specified in the first argument	
    val files = sc.wholeTextFiles(args(0))

    //create an inverted index for those files
    val wordsPerFile = files map {
      case (file, text) =>
        (file.substring(file.lastIndexOf("/", file.lastIndexOf("/") - 1)),
         countWords(text.replace("-", "")))
    }
    val filesPerWord = wordsPerFile flatMap {
      case(file, words) => words map { case (word, count) => (word, (file, count)) }
    } groupByKey() map { case(word, files) => (word, files.toSeq.sortWith(_._2 > _._2)) }

    //save the inverted in mongo using the property 'spark.mongodb.output.uri'
    val mongoRDD = filesPerWord map { case (word, values) =>
      val files = for {
        (name, count) <- values
      } yield Document("file" -> name, "count" -> count)
      Document("word" -> word, "files" -> files)
    } map { new org.bson.Document(_) }
    MongoSpark.save(mongoRDD)
 
    //stop the application
    sc.stop()
  }
}