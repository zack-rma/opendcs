package org.opendcs.gui.validation;

import java.awt.EventQueue;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.JScrollPane;
import java.awt.FlowLayout;
import java.awt.BorderLayout;
import javax.swing.BoxLayout;
import javax.swing.JMenuBar;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.ButtonGroup;

public class ScreeningEditor extends JFrame {

	private static final long serialVersionUID = 1L;
	private JPanel contentPane;
	private final JPanel status = new JPanel();
	private final ButtonGroup unitSystem = new ButtonGroup();

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					ScreeningEditor frame = new ScreeningEditor();
					frame.setVisible(true);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Create the frame.
	 */
	public ScreeningEditor() {
		setTitle("ScreeningEditor");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setBounds(100, 100, 450, 300);
		setResizable(true);
		JMenuBar menuBar = new JMenuBar();
		setJMenuBar(menuBar);
		
		JMenu mnDatasource = new JMenu("DataSource");
		menuBar.add(mnDatasource);
		
		JMenuItem mntmConnect = new JMenuItem("Connect");
		mnDatasource.add(mntmConnect);
		
		JMenu mnUnits = new JMenu("Units");
		menuBar.add(mnUnits);
		
		JRadioButtonMenuItem rdbtnmntmSi = new JRadioButtonMenuItem("Metric");
		unitSystem.add(rdbtnmntmSi);
		rdbtnmntmSi.setSelected(true);
		mnUnits.add(rdbtnmntmSi);
		
		JRadioButtonMenuItem rdbtnmntmEnglish = new JRadioButtonMenuItem("US Customary");
		unitSystem.add(rdbtnmntmEnglish);
		mnUnits.add(rdbtnmntmEnglish);
		contentPane = new JPanel();
		contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));

		setContentPane(contentPane);
		contentPane.setLayout(new BorderLayout(0, 0));
		
		JSplitPane splitPane = new JSplitPane();
		contentPane.add(splitPane);
		
		JScrollPane scrollPane = new JScrollPane();
		splitPane.setLeftComponent(scrollPane);
		
		JTree screenings = new JTree();
		scrollPane.setViewportView(screenings);
		
		JPanel panel_1 = new JPanel();
		splitPane.setRightComponent(panel_1);
		panel_1.setLayout(new BoxLayout(panel_1, BoxLayout.X_AXIS));
		
		JSplitPane splitPane_1 = new JSplitPane();
		splitPane_1.setOrientation(JSplitPane.VERTICAL_SPLIT);
		panel_1.add(splitPane_1);
		
		JScrollPane assignmentsScrollPain = new JScrollPane();
		splitPane_1.setRightComponent(assignmentsScrollPain);
		
		JPanel informationPanel = new JPanel();
		splitPane_1.setLeftComponent(informationPanel);
		FlowLayout fl_status = (FlowLayout) status.getLayout();
		contentPane.add(status, BorderLayout.SOUTH);
	}

}
