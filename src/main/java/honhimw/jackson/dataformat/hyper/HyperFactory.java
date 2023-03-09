/*
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
 */

package honhimw.jackson.dataformat.hyper;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.io.IOContext;
import honhimw.jackson.dataformat.hyper.deser.SheetInput;
import honhimw.jackson.dataformat.hyper.deser.SheetParser;
import honhimw.jackson.dataformat.hyper.deser.SheetReader;
import honhimw.jackson.dataformat.hyper.poi.ooxml.PackageUtil;
import honhimw.jackson.dataformat.hyper.poi.ooxml.SSMLSheetReader;
import honhimw.jackson.dataformat.hyper.poi.ooxml.SSMLWorkbook;
import honhimw.jackson.dataformat.hyper.poi.ss.POISheetReader;
import honhimw.jackson.dataformat.hyper.poi.ss.POISheetWriter;
import honhimw.jackson.dataformat.hyper.schema.HyperSchema;
import honhimw.jackson.dataformat.hyper.ser.SheetOutput;
import honhimw.jackson.dataformat.hyper.ser.SheetWriter;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.TempFile;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@SuppressWarnings("java:S2177")
public final class HyperFactory extends JsonFactory {

    public static final String FORMAT_NAME = "hyper";
    public static final int DEFAULT_SHEET_PARSER_FEATURE_FLAGS = SheetParser.Feature.collectDefaults();

    private final transient WorkbookProvider _workbookProvider;
    private int _sheetParserFeatures;

    public HyperFactory() {
        this(SXSSFWorkbook::new, DEFAULT_SHEET_PARSER_FEATURE_FLAGS);
    }

    public HyperFactory(final WorkbookProvider workbookProvider, final int sheetParserFeatures) {
        _workbookProvider = workbookProvider;
        _sheetParserFeatures = sheetParserFeatures;
    }

    public HyperFactory(final HyperFactory base) {
        _workbookProvider = base._workbookProvider;
        _sheetParserFeatures = base._sheetParserFeatures;
    }

    @Override
    public HyperFactory copy() {
        _checkInvalidCopy(HyperFactory.class);
        return new HyperFactory(this);
    }

    /*
    /**********************************************************
    /* Format detection functionality
    /**********************************************************
     */

    @Override
    public boolean canUseSchema(final FormatSchema schema) {
        return schema instanceof HyperSchema;
    }

    @Override
    public String getFormatName() {
        return FORMAT_NAME;
    }

    /*
    /**********************************************************
    /* Versioned
    /**********************************************************
     */

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    /*
    /**********************************************************
    /* Configuration, sheet parser configuration
    /**********************************************************
     */

    public HyperFactory configure(final SheetParser.Feature f, final boolean state) {
        return state ? enable(f) : disable(f);
    }

    public HyperFactory enable(final SheetParser.Feature f) {
        _sheetParserFeatures |= f.getMask();
        return this;
    }

    public HyperFactory disable(final SheetParser.Feature f) {
        _sheetParserFeatures &= ~f.getMask();
        return this;
    }

    /*
    /**********************************************************
    /* Parser factories
    /**********************************************************
     */

    public SheetParser createParser(final Sheet src) {
        final IOContext ctxt = _createContext(_createContentReference(src), false);
        return _createParser(new POISheetReader(src), ctxt);
    }

    @SuppressWarnings("unchecked")
    public SheetParser createParser(final SheetInput<?> src) throws IOException {
        final SheetInput<?> source = _preferRawAsFile(src);
        final boolean resourceManaged = src != source;
        final IOContext ctxt = _createContext(_createContentReference(source), resourceManaged);
        final SheetReader reader;
        if (source.isFile()) {
            reader = _createFileSheetReader((SheetInput<File>) source);
        } else {
            reader = _createInputStreamSheetReader((SheetInput<InputStream>) source);
        }
        return _createParser(reader, ctxt);
    }

    @Override
    public SheetParser createParser(final File src) throws IOException {
        return createParser(SheetInput.source(src));
    }

    @Override
    public SheetParser createParser(final InputStream src) throws IOException {
        return createParser(SheetInput.source(src));
    }

    /*
    /**********************************************************
    /* Generator factories
    /**********************************************************
     */

    public HyperGenerator createGenerator(final Sheet out) {
        final IOContext ctxt = _createContext(_createContentReference(out), false);
        final HyperGenerator g = _createGenerator(new POISheetWriter(out), ctxt);
        g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        return g;
    }

    public HyperGenerator createGenerator(final SheetOutput<?> out) throws IOException {
        final SheetOutput<OutputStream> output = _rawAsOutputStream(out);
        final boolean resourceManaged = out != output;
        final IOContext ctxt = _createContext(_createContentReference(output), resourceManaged);
        return _createGenerator(_createSheetWriter(output), ctxt);
    }

    @Override
    public HyperGenerator createGenerator(final File out, final JsonEncoding enc) throws IOException {
        return createGenerator(SheetOutput.target(out));
    }

    @Override
    public HyperGenerator createGenerator(final OutputStream out, final JsonEncoding enc) throws IOException {
        return createGenerator(SheetOutput.target(out));
    }

    @Override
    public HyperGenerator createGenerator(final OutputStream out) throws IOException {
        return createGenerator(SheetOutput.target(out));
    }

    /*
    /**********************************************************
    /* Factory methods used by factory for creating parser instances,
    /**********************************************************
     */

    private SheetParser _createParser(final SheetReader reader, final IOContext ctxt) {
        return new SheetParser(ctxt, _parserFeatures, _objectCodec, _sheetParserFeatures, reader);
    }

    private SheetReader _createFileSheetReader(final SheetInput<File> src) throws IOException {
        if (PackageUtil.isOOXML(src.getRaw())) {
            return _createSSMLSheetReader(SSMLWorkbook.create(src.getRaw()), src);
        }
        return _createPOISheetReader(WorkbookFactory.create(src.getRaw()), src);
    }

    private SheetReader _createInputStreamSheetReader(final SheetInput<InputStream> src) throws IOException {
        if (PackageUtil.isOOXML(src.getRaw())) {
            return _createSSMLSheetReader(SSMLWorkbook.create(src.getRaw()), src);
        }
        return _createPOISheetReader(WorkbookFactory.create(src.getRaw()), src);
    }

    private SSMLSheetReader _createSSMLSheetReader(final SSMLWorkbook workbook, final SheetInput<?> src) {
        final PackagePart worksheetPart = src.isNamed() ? workbook.getWorksheetPart(src.getName()) : workbook.getWorksheetPartAt(src.getIndex());
        if (worksheetPart == null) {
            throw new IllegalArgumentException("No sheet for " + src);
        }
        return new SSMLSheetReader(worksheetPart, workbook);
    }

    private POISheetReader _createPOISheetReader(final Workbook workbook, final SheetInput<?> src) {
        final Sheet sheet = src.isNamed() ? workbook.getSheet(src.getName()) : workbook.getSheetAt(src.getIndex());
        if (sheet == null) {
            throw new IllegalArgumentException("No sheet for " + src);
        }
        return new POISheetReader(sheet);
    }

    @SuppressWarnings("unchecked")
    private SheetInput<?> _preferRawAsFile(final SheetInput<?> src) throws IOException {
        if (src.isFile()) return src;
        final InputStream raw = ((SheetInput<InputStream>) src).getRaw();
        if (!PackageUtil.isOOXML(raw)) return src;
        final File file = TempFile.createTempFile("sheet-input", ".xlsx");
        Files.copy(raw, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (isEnabled(StreamReadFeature.AUTO_CLOSE_SOURCE)) {
            raw.close();
        }
        return src.isNamed() ? SheetInput.source(file, src.getName()) : SheetInput.source(file, src.getIndex());
    }

    /*
    /**********************************************************
    /* Factory methods used by factory for creating generator instances,
    /**********************************************************
     */

    private HyperGenerator _createGenerator(final SheetWriter writer, final IOContext ctxt) {
        return new HyperGenerator(ctxt, _generatorFeatures, _objectCodec, writer);
    }

    @SuppressWarnings("resource")
    private SheetWriter _createSheetWriter(final SheetOutput<?> out) throws IOException {
        final Workbook workbook = _workbookProvider.create();
        final Sheet sheet;
        if (out.isNamed()) {
            sheet = workbook.createSheet(out.getName());
        } else {
            sheet = workbook.createSheet();
        }
        return new POISheetWriter(sheet);
    }

    @SuppressWarnings("unchecked")
    private SheetOutput<OutputStream> _rawAsOutputStream(final SheetOutput<?> out) throws IOException {
        if (!out.isFile()) return (SheetOutput<OutputStream>) out;
        final OutputStream raw = Files.newOutputStream(((File) out.getRaw()).toPath());
        return out.isNamed() ? SheetOutput.target(raw, out.getName()) : SheetOutput.target(raw);
    }
}
