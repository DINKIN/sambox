/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sejda.sambox.pdmodel;

import static java.util.Objects.nonNull;
import static java.util.Optional.ofNullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.sejda.sambox.contentstream.PDContentStream;
import org.sejda.sambox.cos.COSArray;
import org.sejda.sambox.cos.COSArrayList;
import org.sejda.sambox.cos.COSBase;
import org.sejda.sambox.cos.COSDictionary;
import org.sejda.sambox.cos.COSFloat;
import org.sejda.sambox.cos.COSName;
import org.sejda.sambox.cos.COSNumber;
import org.sejda.sambox.cos.COSObjectable;
import org.sejda.sambox.cos.COSStream;
import org.sejda.sambox.pdmodel.common.PDMetadata;
import org.sejda.sambox.pdmodel.common.PDRectangle;
import org.sejda.sambox.pdmodel.common.PDStream;
import org.sejda.sambox.pdmodel.interactive.action.PDPageAdditionalActions;
import org.sejda.sambox.pdmodel.interactive.annotation.PDAnnotation;
import org.sejda.sambox.pdmodel.interactive.pagenavigation.PDThreadBead;
import org.sejda.sambox.pdmodel.interactive.pagenavigation.PDTransition;
import org.sejda.sambox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A page in a PDF document.
 * 
 * @author Ben Litchfield
 */
public class PDPage implements COSObjectable, PDContentStream
{
    private static final Logger LOG = LoggerFactory.getLogger(PDPage.class);

    private final COSDictionary page;
    private PDResources pageResources;
    private ResourceCache resourceCache;
    private PDRectangle mediaBox;

    /**
     * Creates a new PDPage instance for embedding, with a size of U.S. Letter (8.5 x 11 inches).
     */
    public PDPage()
    {
        this(PDRectangle.LETTER);
    }

    /**
     * Creates a new instance of PDPage for embedding.
     * 
     * @param mediaBox The MediaBox of the page.
     */
    public PDPage(PDRectangle mediaBox)
    {
        page = new COSDictionary();
        page.setItem(COSName.TYPE, COSName.PAGE);
        page.setItem(COSName.MEDIA_BOX, mediaBox);
    }

    /**
     * Creates a new instance of PDPage for reading.
     * 
     * @param pageDictionary A page dictionary in a PDF document.
     */
    public PDPage(COSDictionary pageDictionary)
    {
        page = pageDictionary;
    }

    /**
     * Creates a new instance of PDPage for reading.
     *
     * @param pageDictionary A page dictionary in a PDF document.
     */
    PDPage(COSDictionary pageDictionary, ResourceCache resourceCache)
    {
        page = pageDictionary;
        this.resourceCache = resourceCache;
    }

    /**
     * Convert this standard java object to a COS object.
     * 
     * @return The cos object that matches this Java object.
     */
    @Override
    public COSDictionary getCOSObject()
    {
        return page;
    }

    /**
     * Returns the content streams which make up this page.
     * 
     * @return content stream iterator
     */
    public Iterator<PDStream> getContentStreams()
    {
        List<PDStream> streams = new ArrayList<>();
        COSBase base = page.getDictionaryObject(COSName.CONTENTS);
        if (base instanceof COSStream)
        {
            streams.add(new PDStream((COSStream) base));
        }
        else if (base instanceof COSArray && ((COSArray) base).size() > 0)
        {
            COSArray array = (COSArray) base;
            for (int i = 0; i < array.size(); i++)
            {
                COSStream stream = (COSStream) array.getObject(i);
                streams.add(new PDStream(stream));
            }
        }
        return streams.iterator();
    }

    @Override
    public InputStream getContents() throws IOException
    {
        COSBase base = page.getDictionaryObject(COSName.CONTENTS);
        if (base instanceof COSStream)
        {
            return ((COSStream) base).getUnfilteredStream();
        }
        else if (base instanceof COSArray && ((COSArray) base).size() > 0)
        {
            COSArray streams = (COSArray) base;
            byte[] delimiter = new byte[] { '\n' };
            List<InputStream> inputStreams = new ArrayList<>();
            for (int i = 0; i < streams.size(); i++)
            {
                COSStream stream = (COSStream) streams.getObject(i);
                if (nonNull(stream))
                {
                    inputStreams.add(stream.getUnfilteredStream());
                    inputStreams.add(new ByteArrayInputStream(delimiter));
                }
            }
            return new SequenceInputStream(Collections.enumeration(inputStreams));
        }
        return new ByteArrayInputStream(new byte[0]);
    }

    /**
     * Returns true if this page has contents.
     */
    public boolean hasContents()
    {
        COSBase contents = page.getDictionaryObject(COSName.CONTENTS);
        if (contents instanceof COSStream)
        {
            return ((COSStream) contents).size() > 0;
        }
        else if (contents instanceof COSArray)
        {
            return ((COSArray) contents).size() > 0;
        }
        return false;
    }

    /**
     * A dictionary containing any resources required by the page.
     */
    @Override
    public PDResources getResources()
    {
        if (pageResources == null)
        {
            pageResources = new PDResources(ofNullable(
                    (COSDictionary) PDPageTree.getInheritableAttribute(page, COSName.RESOURCES))
                            .orElseGet(() -> {
                                COSDictionary emptyRes = new COSDictionary();
                                // it's illegal for a page to not have resources, either direct or inherited. According
                                // to the specs "If the page requires no resources, the value of this entry shall be an
                                // empty dictionary." so we fix it.
                                page.setItem(COSName.RESOURCES, emptyRes);
                                return emptyRes;
                            }),
                    resourceCache);
        }
        return pageResources;
    }

    /**
     * This will set the resources for this page.
     * 
     * @param resources The new resources for this page.
     */
    public void setResources(PDResources resources)
    {
        pageResources = resources;
        if (resources != null)
        {
            page.setItem(COSName.RESOURCES, resources);
        }
        else
        {
            page.removeItem(COSName.RESOURCES);
        }
    }

    /**
     * This will get the key of this Page in the structural parent tree.
     * 
     * @return the integer key of the page's entry in the structural parent tree
     */
    public int getStructParents()
    {
        return page.getInt(COSName.STRUCT_PARENTS, 0);
    }

    /**
     * This will set the key for this page in the structural parent tree.
     * 
     * @param structParents The new key for this page.
     */
    public void setStructParents(int structParents)
    {
        page.setInt(COSName.STRUCT_PARENTS, structParents);
    }

    @Override
    public PDRectangle getBBox()
    {
        return getCropBox();
    }

    @Override
    public Matrix getMatrix()
    {
        // todo: take into account user-space unit redefinition as scale?
        return new Matrix();
    }

    /**
     * A rectangle, expressed in default user space units, defining the boundaries of the physical medium on which the
     * page is intended to be displayed or printed.
     */
    public PDRectangle getMediaBox()
    {
        if (mediaBox == null)
        {
            COSArray array = (COSArray) PDPageTree.getInheritableAttribute(page, COSName.MEDIA_BOX);
            if (array != null)
            {
                mediaBox = new PDRectangle(array);
            }
        }
        if (mediaBox == null)
        {
            LOG.debug("Can't find MediaBox, will use U.S. Letter");
            mediaBox = PDRectangle.LETTER;
        }
        return mediaBox;
    }

    /**
     * This will set the mediaBox for this page.
     * 
     * @param mediaBox The new mediaBox for this page.
     */
    public void setMediaBox(PDRectangle mediaBox)
    {
        this.mediaBox = mediaBox;
        if (mediaBox == null)
        {
            page.removeItem(COSName.MEDIA_BOX);
        }
        else
        {
            page.setItem(COSName.MEDIA_BOX, mediaBox);
        }
    }

    /**
     * A rectangle, expressed in default user space units, defining the visible region of default user space. When the
     * page is displayed or printed, its contents are to be clipped (cropped) to this rectangle.
     */
    public PDRectangle getCropBox()
    {
        COSArray array = (COSArray) PDPageTree.getInheritableAttribute(page, COSName.CROP_BOX);
        if (array != null && inMediaBoxBounds(new PDRectangle(array)))
        {
            return new PDRectangle(array);
        }
        return getMediaBox();
    }

    /**
     * This will set the CropBox for this page.
     * 
     * @param cropBox The new CropBox for this page.
     */
    public void setCropBox(PDRectangle cropBox)
    {
        if (cropBox == null)
        {
            page.removeItem(COSName.CROP_BOX);
        }
        else
        {
            page.setItem(COSName.CROP_BOX, cropBox.getCOSObject());
        }
    }

    /**
     * A rectangle, expressed in default user space units, defining the region to which the contents of the page should
     * be clipped when output in a production environment. The default is the CropBox.
     * 
     * @return The BleedBox attribute.
     */
    public PDRectangle getBleedBox()
    {
        COSArray array = page.getDictionaryObject(COSName.BLEED_BOX, COSArray.class);
        if (nonNull(array) && inMediaBoxBounds(new PDRectangle(array)))
        {
            return new PDRectangle(array);
        }
        return getCropBox();
    }

    /**
     * This will set the BleedBox for this page.
     * 
     * @param bleedBox The new BleedBox for this page.
     */
    public void setBleedBox(PDRectangle bleedBox)
    {
        if (bleedBox == null)
        {
            page.removeItem(COSName.BLEED_BOX);
        }
        else
        {
            page.setItem(COSName.BLEED_BOX, bleedBox);
        }
    }

    /**
     * A rectangle, expressed in default user space units, defining the intended dimensions of the finished page after
     * trimming. The default is the CropBox.
     * 
     * @return The TrimBox attribute.
     */
    public PDRectangle getTrimBox()
    {
        COSArray array = (COSArray) page.getDictionaryObject(COSName.TRIM_BOX);
        if (nonNull(array) && inMediaBoxBounds(new PDRectangle(array)))
        {
            return new PDRectangle(array);
        }
        return getCropBox();
    }

    /**
     * This will set the TrimBox for this page.
     * 
     * @param trimBox The new TrimBox for this page.
     */
    public void setTrimBox(PDRectangle trimBox)
    {
        if (trimBox == null)
        {
            page.removeItem(COSName.TRIM_BOX);
        }
        else
        {
            page.setItem(COSName.TRIM_BOX, trimBox);
        }
    }

    /**
     * A rectangle, expressed in default user space units, defining the extent of the page's meaningful content
     * (including potential white space) as intended by the page's creator The default is the CropBox.
     * 
     * @return The ArtBox attribute.
     */
    public PDRectangle getArtBox()
    {
        COSArray array = page.getDictionaryObject(COSName.ART_BOX, COSArray.class);
        if (nonNull(array) && inMediaBoxBounds(new PDRectangle(array)))
        {
            return new PDRectangle(array);
        }
        return getCropBox();
    }

    /**
     * This will set the ArtBox for this page.
     * 
     * @param artBox The new ArtBox for this page.
     */
    public void setArtBox(PDRectangle artBox)
    {
        if (artBox == null)
        {
            page.removeItem(COSName.ART_BOX);
        }
        else
        {
            page.setItem(COSName.ART_BOX, artBox);
        }
    }

    /**
     * @return true if the given box fits into the media box
     */
    private boolean inMediaBoxBounds(PDRectangle box) {
        PDRectangle mediaBox = getMediaBox();
        return mediaBox.getLowerLeftX() <= box.getLowerLeftX() &&
                mediaBox.getLowerLeftY() <= box.getLowerLeftY() &&
                mediaBox.getUpperRightX() >= box.getUpperRightX() &&
                mediaBox.getUpperRightY() >= box.getUpperRightY();
    }

    /**
     * Returns the rotation angle in degrees by which the page should be rotated clockwise when displayed or printed.
     * Valid values in a PDF must be a multiple of 90.
     *
     * @return The rotation angle in degrees in normalized form (0, 90, 180 or 270) or 0 if invalid or not set at this
     * level.
     */
    public int getRotation()
    {
        COSBase obj = PDPageTree.getInheritableAttribute(page, COSName.ROTATE);
        if (obj instanceof COSNumber)
        {
            int rotationAngle = ((COSNumber) obj).intValue();
            if (rotationAngle % 90 == 0)
            {
                return (rotationAngle % 360 + 360) % 360;
            }
        }
        return 0;
    }

    /**
     * This will set the rotation for this page.
     * 
     * @param rotation The new rotation for this page in degrees.
     */
    public void setRotation(int rotation)
    {
        page.setInt(COSName.ROTATE, rotation);
    }

    /**
     * This will set the contents of this page.
     * 
     * @param contents The new contents of the page.
     */
    public void setContents(PDStream contents)
    {
        page.setItem(COSName.CONTENTS, contents);
    }

    /**
     * This will set the contents of this page.
     * 
     * @param contents Array of new contents of the page.
     */
    public void setContents(List<PDStream> contents)
    {
        COSArray array = new COSArray();
        for (PDStream stream : contents)
        {
            array.add(stream);
        }
        page.setItem(COSName.CONTENTS, array);
    }

    /**
     * This will get a list of PDThreadBead objects, which are article threads in the document. This will return an
     * empty list of there are no thread beads.
     * 
     * @return A list of article threads on this page.
     */
    public List<PDThreadBead> getThreadBeads()
    {
        COSArray beads = page.getDictionaryObject(COSName.B, COSArray.class);
        if (beads == null)
        {
            return new COSArrayList<>(page, COSName.B);
        }
        List<PDThreadBead> actuals = new ArrayList<>();
        for (int i = 0; i < beads.size(); i++)
        {
            COSBase item = beads.getObject(i);
            PDThreadBead bead = ofNullable(item).filter(d -> d instanceof COSDictionary)
                    .map(COSDictionary.class::cast).map(PDThreadBead::new).orElseGet(() -> {
                        LOG.warn("Ignored thread bead expected to be a dictionary but was {}",
                                item);
                        return null;
                    });
            if (nonNull(bead))
            {
                actuals.add(bead);
            }
        }
        return new COSArrayList<>(actuals, beads);
    }

    /**
     * This will set the list of thread beads.
     * 
     * @param beads A list of PDThreadBead objects or null.
     */
    public void setThreadBeads(List<PDThreadBead> beads)
    {
        page.setItem(COSName.B, COSArrayList.converterToCOSArray(beads));
    }

    /**
     * Get the metadata that is part of the document catalog. This will return null if there is no meta data for this
     * object.
     * 
     * @return The metadata for this object.
     */
    public PDMetadata getMetadata()
    {
        return ofNullable(page.getDictionaryObject(COSName.METADATA, COSStream.class))
                .map(PDMetadata::new).orElse(null);
    }

    /**
     * Set the metadata for this object. This can be null.
     * 
     * @param meta The meta data for this object.
     */
    public void setMetadata(PDMetadata meta)
    {
        page.setItem(COSName.METADATA, meta);
    }

    /**
     * Get the page actions.
     * 
     * @return The Actions for this Page
     */
    public PDPageAdditionalActions getActions()
    {
        COSDictionary addAct = page.getDictionaryObject(COSName.AA, COSDictionary.class);
        if (addAct == null)
        {
            addAct = new COSDictionary();
            page.setItem(COSName.AA, addAct);
        }
        return new PDPageAdditionalActions(addAct);
    }

    /**
     * Set the page actions.
     * 
     * @param actions The actions for the page.
     */
    public void setActions(PDPageAdditionalActions actions)
    {
        page.setItem(COSName.AA, actions);
    }

    /**
     * @return The page transition associated with this page or null if no transition is defined
     */
    public PDTransition getTransition()
    {
        return ofNullable(page.getDictionaryObject(COSName.TRANS, COSDictionary.class))
                .map(PDTransition::new).orElse(null);
    }

    /**
     * @param transition The new transition to set on this page.
     */
    public void setTransition(PDTransition transition)
    {
        page.setItem(COSName.TRANS, transition);
    }

    /**
     * Convenient method to set a transition and the display duration
     * 
     * @param transition The new transition to set on this page.
     * @param duration The maximum length of time, in seconds, that the page shall be displayed during presentations
     * before the viewer application shall automatically advance to the next page.
     */
    public void setTransition(PDTransition transition, float duration)
    {
        page.setItem(COSName.TRANS, transition);
        page.setItem(COSName.DUR, new COSFloat(duration));
    }

    /**
     * This will return a list of the Annotations for this page.
     * 
     * @return List of the PDAnnotation objects, never null.
     */
    public List<PDAnnotation> getAnnotations()
    {
        COSArray annots = page.getDictionaryObject(COSName.ANNOTS, COSArray.class);
        if (annots == null)
        {
            return new COSArrayList<>(page, COSName.ANNOTS);
        }
        List<PDAnnotation> actuals = new ArrayList<>();
        for (int i = 0; i < annots.size(); i++)
        {
            COSBase item = annots.getObject(i);
            PDAnnotation annotation = ofNullable(item).filter(d -> d instanceof COSDictionary)
                    .map(COSDictionary.class::cast).map(PDAnnotation::createAnnotation)
                    .orElseGet(() -> {
                        LOG.warn("Ignored annotation expected to be a dictionary but was {}", item);
                        return null;
                    });
            if (nonNull(annotation))
            {
                actuals.add(annotation);
            }
        }
        return new COSArrayList<>(actuals, annots);
    }

    /**
     * This will set the list of annotations.
     * 
     * @param annotations The new list of annotations.
     */
    public void setAnnotations(List<PDAnnotation> annotations)
    {
        page.setItem(COSName.ANNOTS, COSArrayList.converterToCOSArray(annotations));
    }

    @Override
    public boolean equals(Object other)
    {
        return other instanceof PDPage && ((PDPage) other).getCOSObject() == this.getCOSObject();
    }

    @Override
    public int hashCode()
    {
        return page.hashCode();
    }

    /**
     * Returns the resource cache associated with this page, or null if there is none.
     */
    public ResourceCache getResourceCache()
    {
        return resourceCache;
    }
}
