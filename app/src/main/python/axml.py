# axml.py – wrapper around pyaxmlparser
from pyaxmlparser.axmlprinter import AXMLPrinter

class AXML:
    """
    Wrapper for decoding AndroidManifest.xml (binary AXML) to XML.
    Note: pyaxmlparser does NOT support encoding.
    """

    def init(self):
        self._xml = ""
        self._raw = b""

    # ---------- decoder ----------
    def parse_file(self, path: str):
        with open(path, 'rb') as f:
            self._raw = f.read()
        try:
            axml = AXMLPrinter(self._raw)
            self._xml = axml.get_xml()
        except Exception as e:
            raise RuntimeError(f"Failed to parse AXML: {e}")

    def get_xml(self) -> str:
        return self._xml

    # ---------- encoder ----------
    def from_xml(self, xml_str: str):
        # Stub – encoding not supported
        self._xml = xml_str  # this line just stores it temporarily

    def get_buff(self) -> bytes:
        # Since encoding AXML is unsupported, return original
        return self._raw