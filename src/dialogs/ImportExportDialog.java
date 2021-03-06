package dialogs;

import java.awt.Dialog;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

import java.nio.charset.*;

import java.util.Set;

import java.util.logging.Logger;

import javax.swing.*;

import javax.swing.filechooser.FileNameExtensionFilter;

import base.*;

import graphics.CheckBoxList;
import record.*;

import java.util.*;

import graphics.ComboBoxFromFile;

import record.RomReader;

// TODO: enforce data sizes when exporting

public class ImportExportDialog {
	static Logger logger = Logger.getLogger(ImportExportDialog.class.getName());

	public static final int EXPORT_VERSION = 1;
	public static final int HEADER_SIZE = 0x40;


	abstract class ExportableData {
		boolean enabled = true;
		void setEnabled(boolean val) {
			enabled = val;
		}
		boolean isEnabled() {
			return enabled;
		}

		abstract String getName();
		abstract int exportData(ByteArrayOutputStream out) throws IOException;
		abstract boolean importData(byte[] data, int offset);
	}

	ExportableData[] exportableData = new ExportableData[] {
		new ExportableData() {
			String getName() {
				return "Level Data";
			}

			int exportData(ByteArrayOutputStream out) throws IOException {
				// Keeps track of which leveldata has been written already
				HashMap<Object, Integer> writtenLevelData = new HashMap<Object, Integer>();
				// Same for warpdata
				HashMap<Object, Integer> writtenWarpData = new HashMap<Object, Integer>();

				ByteArrayOutputStream dataOutput = new ByteArrayOutputStream();

				int dataPos = HEADER_SIZE + out.size() + (3*4)*levelsToExport.size() + 4;

				for (Level level : levelsToExport) {
					// Check if the level's tile & object data has a pointer already
					Integer tileObjectPointer = writtenLevelData.get(level.getLevelDataRecord());

					if (tileObjectPointer == null) {
						// Write a new set of tile data and object data to the export
						tileObjectPointer = dataPos;
						dataPos += 0xa0*0x30 + 0xa0*0x30/2;
						writtenLevelData.put(level.getLevelDataRecord(), tileObjectPointer);

						dataOutput.write(level.getTileDataRecord().toArray());
						dataOutput.write(level.getObjectDataRecord().toArray());
					}

					// Same for warp data
					Integer warpDataPointer = writtenWarpData.get(level.getRegionDataRecord());

					if (warpDataPointer == null) {
						warpDataPointer = dataPos;
						dataPos += 0xa*0x3*8;
						writtenWarpData.put(level.getRegionDataRecord(), warpDataPointer);

						dataOutput.write(level.getRegionDataRecord().getRawWarpData());
					}

					writeInt(out, level.getId());
					writeInt(out, tileObjectPointer);
					writeInt(out, warpDataPointer);
				}
				writeInt(out, -1);
				out.write(dataOutput.toByteArray());
				return 0;
			}

			boolean importData(byte[] data, int offset) throws ArrayIndexOutOfBoundsException {
				ByteArrayInputStream in = new ByteArrayInputStream(data, offset, data.length-offset);
				HashMap<Integer, Level> readLevelData = new HashMap<Integer,Level>();
				HashMap<Integer, Level> readWarpData = new HashMap<Integer,Level>();

				int index = readInt(in);

				while (index != -1) {
					logger.fine("Importing level " + RomReader.toHexString(index, 2));

					// Offsets of data in file
					int tileObjectDataPointer = readInt(in);
					int warpDataPointer = readInt(in);

					Level level = Level.getLevel(index);

					// Tile, Object data
					Level mergeLevel = readLevelData.get(tileObjectDataPointer);
					if (mergeLevel == null) {
						// Data has not been read yet, create the data and give it to the level
						byte[] tileData = Arrays.copyOfRange(data,
								tileObjectDataPointer,
								tileObjectDataPointer+0xa0*0x30);
						byte[] objectData = Arrays.copyOfRange(data,
								tileObjectDataPointer+0xa0*0x30,
								tileObjectDataPointer+0xa0*0x30+0xa0*0x30/2);

						level.setNewLevelData(tileData, objectData);

						readLevelData.put(tileObjectDataPointer, level);
					}
					else {
						// Data has been read already, merge the current level's data with it
						level.mergeLevelDataWith(mergeLevel);
					}

					// Regions
					mergeLevel = readWarpData.get(warpDataPointer);
					if (mergeLevel == null) {
						byte[] warpData = Arrays.copyOfRange(data,
								warpDataPointer,
								warpDataPointer+RegionRecord.NUM_SECTORS*8);
						level.setNewRegionData(warpData);

						readWarpData.put(warpDataPointer, level);
					}
					else {
						level.setRegionDataRecord(mergeLevel.getRegionDataRecord());
					}

					index = readInt(in);
				}

				return true;
			}
		},
		new ExportableData() {
			String getName() {
				return "Tileset Data";
			}

			int exportData(ByteArrayOutputStream out) throws IOException {
				Set<TileSet> tileSets = new HashSet<TileSet>();
				Set<Integer> metaTiles = new HashSet<Integer>();
				Set<Integer> flags = new HashSet<Integer>();
				Set<Integer> palettes = new HashSet<Integer>();
				Set<Integer> bank0Gfxs = new HashSet<Integer>();
				Set<Integer> bank1Gfxs = new HashSet<Integer>();

				for (Level l : levelsToExport) {
					RegionRecord regionRecord = l.getRegionDataRecord();
					for (int i=0; i<regionRecord.getNumRegions(); i++) {
						tileSets.add(regionRecord.getRegion(i).getTileSet());
					}
				}

				for (TileSet tileSet : tileSets) {
					metaTiles.add(tileSet.getMetaTileIndex());
					flags.add(tileSet.getFlagIndex());
					palettes.add(tileSet.getPaletteDataIndex());
					bank0Gfxs.add(tileSet.getGfxData0Index());
					bank1Gfxs.add(tileSet.getGfxData1Index());
				}

				// Start writing everything

				// Tileset data
				for (TileSet tileSet : tileSets) {
					writeInt(out, tileSet.getIndex());
					out.write(tileSet.getTileSetDataRecord().toArray());
				}
				writeInt(out, -1);
				// Metatile data: includes both metatiles and effects (both use the same index)
				logger.finer("Exporting metatiles");
				for (Integer index : metaTiles) {
					writeInt(out, index);
					out.write(TileSet.getMetaTileRecord(index).toArray());
					out.write(TileSet.getEffectRecord(index).toArray());
				}
				writeInt(out, -1);
				// Flag data
				logger.finer("Exporting flags");
				for (Integer index : flags) {
					writeInt(out, index);
					out.write(TileSet.getFlagRecord(index).toArray());
				}
				writeInt(out, -1);
				// Palette data
				logger.finer("Exporting palettes");
				for (Integer index : palettes) {
					writeInt(out, index);
					out.write(TileSet.getPaletteDataRecord(index).toArray());
				}
				writeInt(out, -1);
				// Bank 0 Gfx
				logger.finer("Exporting gfx bank 0");
				for (Integer index : bank0Gfxs) {
					writeInt(out, index);
					out.write(TileSet.getGfxData0Record(index).toArray());
				}
				writeInt(out, -1);
				// Bank 1 Gfx
				logger.finer("Exporting gfx bank 1");
				for (Integer index : bank1Gfxs) {
					writeInt(out, index);
					out.write(TileSet.getGfxData1Record(index).toArray());
				}
				writeInt(out, -1);

				return 0;
			}
			boolean importData(byte[] data, int offset) {
				ByteArrayInputStream in = new ByteArrayInputStream(data, offset, data.length-offset);

				int index;

				// Tileset data
				logger.finer("Importing tileset data");
				index = readInt(in);
				while (index != -1) {
					byte[] buf = new byte[5];
					in.read(buf, 0, 5);
					TileSet.getTileSet(index).getTileSetDataRecord().setData(buf);
					index = readInt(in);
				}
				// Metatile and effect data
				logger.finer("Importing metatile data");
				index = readInt(in);
				while (index != -1) {
					byte[] buf = new byte[0x80*4];
					in.read(buf, 0, 0x80*4);
					TileSet.getMetaTileRecord(index).setData(buf);
					buf = new byte[0x80*2];
					in.read(buf, 0, 0x80*2);
					TileSet.getEffectRecord(index).setData(buf);
					index = readInt(in);
				}
				// Flag data
				index = readInt(in);
				while (index != -1) {
					logger.finer("Importing flag data " + RomReader.toHexString(index));
					byte[] buf = new byte[0x80*4];
					in.read(buf, 0, 0x80*4);
					TileSet.getFlagRecord(index).setData(buf);
					index = readInt(in);
				}
				// Palette data
				logger.finer("Importing palette data");
				index = readInt(in);
				while (index != -1) {
					byte[] buf = new byte[8*4*2];
					in.read(buf, 0, 8*4*2);
					TileSet.getPaletteDataRecord(index).setData(buf);
					index = readInt(in);
				}
				// Gfx 0 data
				logger.finer("Importing gfx bank 0 data");
				index = readInt(in);
				while (index != -1) {
					byte[] buf = new byte[128*16];
					in.read(buf, 0, 128*16);
					TileSet.getGfxData0Record(index).setData(buf);
					index = readInt(in);
				}
				// Gfx 1 data
				logger.finer("Importing gfx bank 1 data");
				index = readInt(in);
				while (index != -1) {
					if (bank1CheckBox.isSelected()) {
						byte[] buf = new byte[128*16];
						in.read(buf, 0, 128*16);
						TileSet.getGfxData1Record(index).setData(buf);
					}
					index = readInt(in);
				}

				TileSet.invalidateAllImages();

				return true;
			}
		},
		new ExportableData() {
			String getName() {
				return "ObjectSet Data";
			}

			public int exportData(ByteArrayOutputStream out) throws IOException {
				Set<ObjectSet> objectSets = new HashSet<ObjectSet>();

				for (Level level : levelsToExport) {
					RegionRecord r = level.getRegionDataRecord();
					for (int i=0; i<r.getNumRegions(); i++) {
						int objectSet = r.getRegion(i).objectSetId;
						objectSets.add(ObjectSet.getObjectSet(objectSet));
					}
				}

				for (ObjectSet objectSet : objectSets) {
					writeInt(out, objectSet.getId());
					writeInt(out, objectSet.getItemSetAddr());
					writeString(out, objectSet.getEnemySet().getName());
				}
				writeInt(out, -1);

				return 0;
			}
			public boolean importData(byte[] data, int offset) {
				ByteArrayInputStream in = new ByteArrayInputStream(data, offset, data.length-offset);

				int index = readInt(in);
				while (index != -1) {
					int itemSetAddr = readInt(in);
					String enemySetName = readString(in);

					ObjectSet objectSet = ObjectSet.getObjectSet(index);
					objectSet.setItemSetAddr(itemSetAddr);
					EnemySet e = EnemySet.getEnemySet(enemySetName);
					if (e == null) {
						logger.warning("Null enemy set import");
					}
					else {
						objectSet.setEnemySet(e);
					}

					index = readInt(in);
				}

				return true;
			}
		},
		new ExportableData() {
			String getName() {
				return "EnemySet Data";
			}

			public int exportData(ByteArrayOutputStream out) throws IOException {
				Set<EnemySet> enemySets = new HashSet<EnemySet>();

				for (Level level : levelsToExport) {
					RegionRecord r = level.getRegionDataRecord();
					for (int i=0; i<r.getNumRegions(); i++) {
						int objectSet = r.getRegion(i).objectSetId;
						enemySets.add(ObjectSet.getObjectSet(objectSet).getEnemySet());
					}
				}

				for (EnemySet enemySet : enemySets) {
					byte[] data = enemySet.getData();

					writeString(out, enemySet.getName());
					writeInt(out, data.length);
					out.write(data);
				}
				writeString(out, "");

				return 0;
			}
			public boolean importData(byte[] data, int offset) {
				ByteArrayInputStream in = new ByteArrayInputStream(data, offset, data.length-offset);

				String name = readString(in);
				while (!name.isEmpty()) {
					logger.finer("Importing enemy set " + name);

					int dataSize = readInt(in);
					byte[] enemySetData = new byte[dataSize];
					in.read(enemySetData, 0, dataSize);

					EnemySet enemySet = EnemySet.getEnemySet(name);
					enemySet.setData(enemySetData);

					name = readString(in);
				}

				return true;
			}
		},
	};



	Set<Level> levelsToExport;

	JDialog dialog;

	JCheckBox[] checkBoxes;
	JCheckBox bank1CheckBox;


	// Kind of silly to make this a dialog, I don't use it as one
	public ImportExportDialog(boolean export, int baseLevel) {
		if (export) {
			levelsToExport = new HashSet<Level>();
			for (int i=baseLevel/8*8; i<(baseLevel/8+1)*8; i++) {
				levelsToExport.add(Level.getLevel(i));
			}

			ByteArrayOutputStream data = new ByteArrayOutputStream();

			try {
				writeHeader(data);

				RomReader.exportData(data.toByteArray(),
						"Export Level",
						new FileNameExtensionFilter("WL3 Archive File (.wl3)", "wl3"));
			}
			catch (IOException ex) {
			}
		}
		else { // Import
			dialog = new JDialog(null, "Import Level", Dialog.ModalityType.APPLICATION_MODAL);
			JPanel contentPanel = new JPanel();
			contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

			checkBoxes = new JCheckBox[exportableData.length];
			for (int i=0; i<exportableData.length; i++) {
				ExportableData data = exportableData[i];

				checkBoxes[i] = new JCheckBox(data.getName());
				checkBoxes[i].addItemListener(new ItemListener() {
					public void itemStateChanged(ItemEvent e) {
						int j;
						for (j=0;; j++) {
							if (e.getSource() == checkBoxes[j])
								break;
						}
						exportableData[j].setEnabled(e.getStateChange() == ItemEvent.SELECTED);
					}
				});
				checkBoxes[i].setSelected(true);

				contentPanel.add(checkBoxes[i]);
			}
			bank1CheckBox = new JCheckBox("Bank 1 gfx");
			bank1CheckBox.setSelected(false);
			contentPanel.add(bank1CheckBox);

			JButton importButton = new JButton("Import");
			importButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					byte[] data = RomReader.importData("Import Level",
							new FileNameExtensionFilter("WL3 Archive File (.wl3)", "wl3"));

					if (data != null) {
						if (!readHeader(data)) {
							logger.warning("Import error");
						}
					}

					dialog.setVisible(false);
				}
			});

			contentPanel.add(importButton);

			dialog.add(contentPanel);
			dialog.pack();
			dialog.setVisible(true);
		}
	}

	void writeInt(ByteArrayOutputStream out, int val) {
		out.write(val&0xff);
		out.write((val>>8)&0xff);
		out.write((val>>16)&0xff);
		out.write((val>>24)&0xff);
	}

	void writeString(ByteArrayOutputStream out, String s) throws IOException {
		byte[] data = s.getBytes(StandardCharsets.US_ASCII);
		out.write(data);
		out.write(0);
	}

	int readInt(byte[] data, int i) throws ArrayIndexOutOfBoundsException {
		return (data[i]&0xff) | (data[i+1]&0xff)<<8 | (data[i+2]&0xff)<<16 | (data[i+3]&0xff)<<24;
	}
	int readInt(ByteArrayInputStream in) {
		byte[] data = new byte[4];
		in.read(data, 0, 4);
		return (data[0]&0xff) | (data[1]&0xff)<<8 | (data[2]&0xff)<<16 | (data[3]&0xff)<<24;
	}

	String readString(ByteArrayInputStream in) {
		ByteArrayOutputStream data = new ByteArrayOutputStream();

		int b = in.read();
		while (b != 0) {
			data.write(b);
			b = in.read();
		}
		return new String(data.toByteArray(), StandardCharsets.US_ASCII);
	}

	void writeHeader(ByteArrayOutputStream out) throws IOException {
		// Magic (0x00)
		out.write("WL3E".getBytes(StandardCharsets.US_ASCII));

		// Version (0x04)
		writeInt(out, EXPORT_VERSION);

		// Everything else
		ByteArrayOutputStream dataOutput = new ByteArrayOutputStream();

		for (ExportableData data : exportableData) {
			writeInt(out, dataOutput.size() + HEADER_SIZE);
			data.exportData(dataOutput);
		}

		while (out.size() < HEADER_SIZE)
			out.write(0);

		out.write(dataOutput.toByteArray());
	}

	boolean readHeader(byte[] data) {
		try {
			byte[] magicBytes = Arrays.copyOf(data, 4);
			String magic = new String(magicBytes, StandardCharsets.US_ASCII);
			if (!magic.equals("WL3E")) {
				importErr("Archive is corrupt.");
				return false;
			}

			int version = readInt(data, 4);
			if (version > EXPORT_VERSION) {
				importErr("Archive is from a newer revision.");
				return false;
			}

			int headerPos = 8;
			for (ExportableData importer : exportableData) {
				int pointer = readInt(data, headerPos);
				if (importer.isEnabled())
					importer.importData(data, pointer);
				headerPos += 4;
			}
		}
		catch (ArrayIndexOutOfBoundsException e) {
			importErr("Abrupt end of file.");
		}

		return true;
	}

	void importErr(String message) {
		JOptionPane.showMessageDialog(null,
				"Import Error: " + message,
				"Import Error",
				JOptionPane.ERROR_MESSAGE);
	}
}
