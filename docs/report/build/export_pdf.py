# Fills in the table of contents and exports the report to PDF with LibreOffice.
# Run with LibreOffice's own Python: "C:\Program Files\LibreOffice\program\python.exe" export_pdf.py
import os
import subprocess
import sys
import tempfile
import time

import uno
from com.sun.star.beans import PropertyValue


def prop(name, value):
    p = PropertyValue()
    p.Name, p.Value = name, value
    return p


here = os.path.dirname(os.path.abspath(__file__))
docx = os.path.join(here, '..', 'Ember_Report.docx')
pdf = os.path.join(here, '..', 'Ember_Report.pdf')
soffice = os.path.join(os.path.dirname(sys.executable), 'soffice.exe')
profile = uno.systemPathToFileUrl(tempfile.mkdtemp(prefix='ember-lo-'))  # never touches a running LibreOffice
proc = subprocess.Popen([soffice, '-env:UserInstallation=' + profile, '--headless', '--invisible', '--norestore',
                         '--accept=pipe,name=emberreport;urp;'])
try:
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext('com.sun.star.bridge.UnoUrlResolver', local)
    ctx = None
    for _ in range(120):
        try:
            ctx = resolver.resolve('uno:pipe,name=emberreport;urp;StarOffice.ComponentContext')
            break
        except Exception:
            time.sleep(0.5)
    if ctx is None:
        sys.exit('LibreOffice did not start')
    desktop = ctx.ServiceManager.createInstanceWithContext('com.sun.star.frame.Desktop', ctx)
    doc = desktop.loadComponentFromURL(uno.systemPathToFileUrl(os.path.abspath(docx)), '_blank', 0, (prop('Hidden', True),))
    indexes = doc.getDocumentIndexes()
    for _ in range(2):  # the second pass picks up page shifts caused by the first
        for i in range(indexes.getCount()):
            indexes.getByIndex(i).update()
    doc.storeToURL(uno.systemPathToFileUrl(os.path.abspath(pdf)), (prop('FilterName', 'writer_pdf_Export'),))
    doc.close(True)
    print('wrote ' + os.path.abspath(pdf))
finally:
    try:
        desktop.terminate()
    except Exception:
        pass
    try:
        proc.wait(30)
    except Exception:
        proc.kill()
