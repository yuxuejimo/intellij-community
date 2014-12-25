package com.intellij.openapi.util.diff.tools.fragmented;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.diff.comparison.iterables.DiffIterableUtil.IntPair;
import com.intellij.openapi.util.diff.fragments.DiffFragment;
import com.intellij.openapi.util.diff.fragments.FineLineFragment;
import com.intellij.openapi.util.diff.fragments.LineFragment;
import com.intellij.openapi.util.diff.fragments.LineFragments;
import com.intellij.openapi.util.diff.util.Side;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

// TODO: check for bugs with non-fair differences
class OnesideFragmentBuilder {
  @NotNull private final LineFragments myFragments;
  @NotNull private final Document myDocument1;
  @NotNull private final Document myDocument2;
  private final boolean myInlineFragments;
  @NotNull private final Side myMasterSide;

  @NotNull private final StringBuilder myBuilder = new StringBuilder();
  @NotNull private final List<ChangedBlock> myBlocks = new ArrayList<ChangedBlock>();
  @NotNull private final List<HighlightRange> myRanges = new ArrayList<HighlightRange>();
  @NotNull private final LineNumberConvertor.Builder myConvertor = new LineNumberConvertor.Builder();
  @NotNull private final List<IntPair> myEqualLines = new ArrayList<IntPair>();

  public OnesideFragmentBuilder(@NotNull LineFragments fragments,
                                @NotNull Document document1,
                                @NotNull Document document2,
                                boolean inlineFragments,
                                @NotNull Side masterSide) {
    myFragments = fragments;
    myDocument1 = document1;
    myDocument2 = document2;
    myInlineFragments = inlineFragments;
    myMasterSide = masterSide;
  }

  private boolean myEqual = false;

  private int lastProcessedLine1 = -1;
  private int lastProcessedLine2 = -1;
  private int totalLines = 0;

  public void exec() {
    if (myFragments.getFragments().isEmpty()) {
      myEqual = true;
      appendTextMaster(0, 0, getLineCount(myDocument1) - 1, getLineCount(myDocument2) - 1);
      return;
    }

    for (LineFragment fragment : myFragments.getFragments()) {
      processEquals(fragment.getStartLine1() - 1, fragment.getStartLine2() - 1);
      processChanged(fragment);
    }
    processEquals(getLineCount(myDocument1) - 1, getLineCount(myDocument1) - 2);
  }

  private void processEquals(int endLine1, int endLine2) {
    int startLine1 = lastProcessedLine1 + 1;
    int startLine2 = lastProcessedLine2 + 1;

    appendTextMaster(startLine1, startLine2, endLine1, endLine2);
  }

  private void processChanged(@NotNull LineFragment fragment) {
    int startLine1 = fragment.getStartLine1();
    int endLine1 = fragment.getEndLine1() - 1;
    int lines1 = endLine1 - startLine1;

    int startLine2 = fragment.getStartLine2();
    int endLine2 = fragment.getEndLine2() - 1;
    int lines2 = endLine2 - startLine2;

    int blockStartOffset1;
    int blockEndOffset1;
    int blockStartOffset2;
    int blockEndOffset2;

    int linesBefore = totalLines;
    int linesAfter;

    blockStartOffset1 = myBuilder.length();
    if (lines1 >= 0) {
      int startOffset = myDocument1.getLineStartOffset(startLine1);
      int endOffset = myDocument1.getLineEndOffset(endLine1);

      appendText(Side.LEFT, startOffset, endOffset, lines1, startLine1, -1);
    }
    blockEndOffset1 = myBuilder.length();

    blockStartOffset2 = myBuilder.length();
    if (lines2 >= 0) {
      int startOffset = myDocument2.getLineStartOffset(startLine2);
      int endOffset = myDocument2.getLineEndOffset(endLine2);

      appendText(Side.RIGHT, startOffset, endOffset, lines2, -1, startLine2);
    }
    blockEndOffset2 = myBuilder.length();

    linesAfter = totalLines;

    List<DiffFragment> innerFragments = null;
    if (myInlineFragments && fragment instanceof FineLineFragment) {
      innerFragments = ((FineLineFragment)fragment).getFineFragments();
    }
    myBlocks.add(new ChangedBlock(blockStartOffset1, blockEndOffset1,
                                  blockStartOffset2, blockEndOffset2,
                                  linesBefore, linesAfter, innerFragments));

    lastProcessedLine1 = endLine1;
    lastProcessedLine2 = endLine2;
  }

  private void appendTextMaster(int startLine1, int startLine2, int endLine1, int endLine2) {
    int lines = myMasterSide.isLeft() ? endLine1 - startLine1 : endLine2 - startLine2;

    if (lines >= 0) {
      int startOffset = myMasterSide.isLeft() ? myDocument1.getLineStartOffset(startLine1) : myDocument2.getLineStartOffset(startLine2);
      int endOffset = myMasterSide.isLeft() ? myDocument1.getLineEndOffset(endLine1) : myDocument2.getLineEndOffset(endLine2);

      int linesBefore = totalLines;
      appendText(myMasterSide, startOffset, endOffset, lines, startLine1, startLine2);
      int linesAfter = totalLines;

      myEqualLines.add(new IntPair(linesBefore, linesAfter));
    }
  }

  private void appendText(@NotNull Side side, int offset1, int offset2, int lines, int startLine1, int startLine2) {
    Document document = side.selectN(myDocument1, myDocument2);

    int newline = document.getTextLength() > offset2 + 1 ? 1 : 0;
    TextRange base = new TextRange(myBuilder.length(), myBuilder.length() + offset2 - offset1 + newline);
    TextRange changed = new TextRange(offset1, offset2 + newline);
    myRanges.add(new HighlightRange(side, base, changed));

    myBuilder.append(document.getCharsSequence().subSequence(offset1, offset2));
    myBuilder.append('\n');

    if (startLine1 != -1) {
      myConvertor.put1(totalLines, startLine1, lines + 1);
    }
    if (startLine2 != -1) {
      myConvertor.put2(totalLines, startLine2, lines + 1);
    }

    totalLines += lines + 1;
  }

  private static int getLineCount(@NotNull Document document) {
    return Math.max(document.getLineCount(), 1);
  }

  //
  // Result
  //


  public boolean isEqual() {
    return myEqual;
  }

  @NotNull
  public CharSequence getText() {
    return myBuilder;
  }

  @NotNull
  public List<ChangedBlock> getBlocks() {
    return myBlocks;
  }

  @NotNull
  public List<HighlightRange> getRanges() {
    return myRanges;
  }

  @NotNull
  public LineNumberConvertor getConvertor() {
    return myConvertor.build();
  }

  @NotNull
  public List<IntPair> getEqualLines() {
    return myEqualLines;
  }
}
