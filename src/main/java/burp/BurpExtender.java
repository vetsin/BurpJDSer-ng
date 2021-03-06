package burp;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JMenuItem;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

public class BurpExtender implements IBurpExtender, IMessageEditorTabFactory, IContextMenuFactory, IScannerInsertionPointProvider {

    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;
    protected static ClassLoader loader;
    private static final String LIB_DIR = "./libs/";
    private static PrintStream _stdout;
    private static PrintStream _stderr;
    private static byte[] serializeMagic = new byte[]{-84, -19};

    //
    // implement IBurpExtender
    //
    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {

        // keep a reference to our callbacks object
        this.callbacks = callbacks;

        // obtain an extension helpers object
        helpers = callbacks.getHelpers();

        // get our out/err streams
        BurpExtender._stderr = new PrintStream(callbacks.getStderr());
        BurpExtender._stdout = new PrintStream(callbacks.getStdout());
        
        // set our extension name
        callbacks.setExtensionName("BurpJDSer-ng by omerc.net");

        // register ourselves as a message editor tab factory
        callbacks.registerMessageEditorTabFactory(this);
        callbacks.registerContextMenuFactory(this);
        
        // register ourselves as a scanner insertion point provider
        callbacks.registerScannerInsertionPointProvider(this);
        
    }

    //
    // implement IMessageEditorTabFactory
    //
    @Override
    public IMessageEditorTab createNewInstance(IMessageEditorController controller, boolean editable) {
        // create a new instance of our custom editor tab
        return new SerializedJavaInputTab(controller, editable);
    }

    //
    // implement IContextMenuFactory
    //
    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        List<JMenuItem> menu = new ArrayList<>();
        Action reloadJarsAction = new ReloadJarsAction("BurpJDSer-ng: Reload JARs", invocation);
        JMenuItem reloadJars = new JMenuItem(reloadJarsAction);
        
        menu.add(reloadJars);
        return menu;
    }
    
	private byte[] extractBody(byte[] content, boolean isRequest)
	{
		int offset = -1;
		if(isRequest)
			offset = helpers.analyzeRequest(content).getBodyOffset();
		else 
			offset = helpers.analyzeResponse(content).getBodyOffset();
		if(offset == -1)
			return new byte[]{};
		return Arrays.copyOfRange(content, offset, content.length);
	}
	
	private byte[] extractJavaClass(byte[] data) {
		int magicPos = helpers.indexOf(data, serializeMagic, false, 0, data.length);
		return Arrays.copyOfRange(data, magicPos, data.length);
	}
	
    class ReloadJarsAction extends AbstractAction {

        IContextMenuInvocation invocation;
        
        public ReloadJarsAction(String text, IContextMenuInvocation invocation) {
            super(text);
            this.invocation = invocation;
        }
        
        @Override
        public void actionPerformed(ActionEvent e) {
            _stdout.println("Reloading jars from " + LIB_DIR);
            refreshSharedClassLoader();
        }
        
    }


    //
    // class implementing IMessageEditorTab
    //
    class SerializedJavaInputTab implements IMessageEditorTab {

        private boolean editable;
        private ITextEditor txtInput;
        private byte[] currentMessage;
        private Object obj;
        private byte[] crap;
        private XStream xstream = new XStream(new DomDriver());

        public SerializedJavaInputTab(IMessageEditorController controller, boolean editable) {
            
            this.editable = editable;

            // create an instance of Burp's text editor, to display our deserialized data
            txtInput = callbacks.createTextEditor();
            txtInput.setEditable(editable);
        }
        
        //
        // implement IMessageEditorTab
        //act
        @Override
        public String getTabCaption() {
            return "Deserialized Java";
        }

        @Override
        public Component getUiComponent() {
            return txtInput.getComponent();
        }

        @Override
        public boolean isEnabled(byte[] content, boolean isRequest) {
            // enable this tab for requests containing the serialized "magic" header
            return helpers.indexOf(content, serializeMagic, false, 0, content.length) > -1;
        }

        @Override
        public void setMessage(byte[] content, boolean isRequest) {
            if (content == null) {
                // clear our display
                txtInput.setText(null);
                txtInput.setEditable(false);
            } else {
                CustomLoaderObjectInputStream is = null;
                try {
                    
                    byte[] body = extractBody(content, isRequest);
        			
        			ByteArrayInputStream bais =  new ByteArrayInputStream(extractJavaClass(body));

                    // Use a custom OIS that uses our own ClassLoader
                    
                    is = new CustomLoaderObjectInputStream(bais, getSharedClassLoader());
                    obj = is.readObject();
                    String xml = xstream.toXML(obj);

                    txtInput.setText(xml.getBytes());
                } catch (IOException | ClassNotFoundException ex) {
                    Logger.getLogger(BurpExtender.class.getName()).log(Level.SEVERE, null, ex);
                    txtInput.setText(helpers.stringToBytes("Something went wrong, did you change the body in a bad way?\n\n" + getStackTrace(ex)));
                } finally {
                    try {
                        is.close();
                    } catch (IOException ex) {
                        Logger.getLogger(BurpExtender.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                txtInput.setEditable(editable);
            }

            // remember the displayed content
            currentMessage = content;
        }
        

        @Override
        public byte[] getMessage() {
            // determine whether the user modified the deserialized data
            if (txtInput.isTextModified()) {
                // xstream doen't like newlines
                String xml = helpers.bytesToString(txtInput.getText()).replace("\n", "");
                // reserialize the data
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                        oos.writeObject(xstream.fromXML(xml));
                        oos.flush();
                    }
                } catch (IOException ex) {
                    Logger.getLogger(BurpExtender.class.getName()).log(Level.SEVERE, null, ex);
                }
                // reconstruct our message (add the crap buffer)
                byte[] baObj = baos.toByteArray();
                byte[] newBody = new byte[baObj.length + crap.length];
                System.arraycopy(crap, 0, newBody, 0, crap.length);
                System.arraycopy(baObj, 0, newBody, crap.length, baObj.length);

                return helpers.buildHttpMessage(helpers.analyzeRequest(currentMessage).getHeaders(), newBody);
            } else {
                return currentMessage;
            }
        }

        @Override
        public boolean isModified() {
            return txtInput.isTextModified();
        }

        @Override
        public byte[] getSelectedData() {
            return txtInput.getSelectedText();
        }

        private String getStackTrace(Throwable t) {
            StringWriter stringWritter = new StringWriter();
            PrintWriter printWritter = new PrintWriter(stringWritter, true);
            t.printStackTrace(printWritter);
            printWritter.flush();
            stringWritter.flush();

            return stringWritter.toString();
        }
    }
    
    protected static ClassLoader createURLClassLoader(String libDir) {
		File dependencyDirectory = new File(libDir);
		File[] files = dependencyDirectory.listFiles();
		ArrayList<URL> urls = new ArrayList<>();
	
		for (int i = 0; i < files.length; i++) {
			if (files[i].getName().endsWith(".jar")) {
				try {
					_stdout.println("Loading: " + files[i].getName());
					urls.add(files[i].toURI().toURL());
				} catch (MalformedURLException ex) {
					Logger.getLogger(BurpExtender.class.getName()).log(Level.SEVERE, null, ex);
					_stderr.println("!! Error loading: " + files[i].getName());
				}
			}
		}
	
		return new URLClassLoader(urls.toArray(new URL[urls.size()]));
	}
    
    public static ClassLoader getSharedClassLoader() {
        if(loader == null) {
                refreshSharedClassLoader();
        }
        return loader;
    }
    
    public static void refreshSharedClassLoader() {
        loader = createURLClassLoader(LIB_DIR);
    }

	@Override
	public List<IScannerInsertionPoint> getInsertionPoints(IHttpRequestResponse baseRequestResponse) {
		byte[] content = baseRequestResponse.getRequest();
		if(helpers.indexOf(content, serializeMagic, false, 0, content.length) > -1) {
			// we are a java object
			List<IScannerInsertionPoint> insertionPoints = new ArrayList<IScannerInsertionPoint>();
			
			int count = new ObjectInsertionPoint(content).getInsertionPointCount();
			_stdout.println("Insertion count: " + count);
			for (int i = 0; i <= count; i++) {
				insertionPoints.add(new ObjectInsertionPoint(content, i));
			}
	        return insertionPoints;
		}
		return null;
	}
	
	class ObjectInsertionPoint implements IScannerInsertionPoint
	{
		private String valueMatcher = "<value>(.+?)</value>";
		private XStream xstream = new XStream(new DomDriver());
		private byte[] request;
		private Object obj;
		private String objStr = "";
		private int point = -1;
		private String baseValue = "";

		public ObjectInsertionPoint(byte[] request) {
			this(request, -1);
		}
		
		public ObjectInsertionPoint(byte[] request, int point) {
			this.request = request;
			this.point = point;
			this.deserialize();
			generateBaseValue();
		}
		
        private void deserialize() {
        	CustomLoaderObjectInputStream is = null;
            try {
            	byte[] body = extractBody(this.request, true);
    			
    			ByteArrayInputStream bais =  new ByteArrayInputStream(extractJavaClass(body));

                // Use a custom OIS that uses our own ClassLoader
                is = new CustomLoaderObjectInputStream(bais, getSharedClassLoader());
                this.obj = is.readObject();
                this.objStr = xstream.toXML(obj);
            } catch (IOException | ClassNotFoundException ex) {
                Logger.getLogger(BurpExtender.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                try {
                    is.close();
                } catch (IOException ex) {
                    Logger.getLogger(BurpExtender.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        
        private void generateBaseValue() {
        	Pattern pattern = Pattern.compile(valueMatcher);
    		Matcher matcher = pattern.matcher(this.objStr);
    		int i = 0;
    		while (matcher.find()) {
    			if (i == point) {
    				String value = matcher.group();
        			value = value.substring("<value>".length(), value.length());
        			value = value.substring(0, value.length() - "</value>".length());
        			this.baseValue = value;
        			break;
    			}
    			i++;
    		}
        }

		@Override
		public String getInsertionPointName() {
			return "JDSer Injection Point";
		}

		@Override
		public String getBaseValue() {
			return this.baseValue;
		}
        
        public int getInsertionPointCount() {
        	int i = 0;
    		Pattern pattern = Pattern.compile(valueMatcher);
    		Matcher matcher = pattern.matcher(this.objStr);
    		while (matcher.find()) {
    			i++;
    		}
        	return i;
        }

		@Override
		public byte[] buildRequest(byte[] payload) {
    		String replaced = helpers.bytesToString(payload).replace(this.baseValue, helpers.bytesToString(payload));
			return helpers.stringToBytes(replaced);
		}

		@Override
		public int[] getPayloadOffsets(byte[] payload) {
			// no offsets due to serialization
			return null;
		}

		@Override
		public byte getInsertionPointType() {
			return INS_EXTENSION_PROVIDED;
		}

	}
}