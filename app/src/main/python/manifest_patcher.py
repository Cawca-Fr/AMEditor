import xml.etree.ElementTree as ET
import io

# --- Configuration ---
NS_ANDROID = 'http://schemas.android.com/apk/res/android'

TRACKERS = [
    "com.google.android.gms.ads",
    "com.facebook.ads",
    "com.mopub.mobileads",
    "com.startapp.sdk",
    "com.applovin",
    "com.unity3d.ads",
    "com.vungle.warren",
    "com.ironsource.sdk",
    "com.google.firebase.analytics",
    "com.google.android.gms.measurement"
]

PERMISSIONS_TO_REMOVE = [
    "android.permission.READ_PHONE_STATE",
    "android.permission.GET_ACCOUNTS",
    "com.google.android.gms.permission.AD_ID"
]

def register_namespaces():
    try:
        ET.register_namespace('android', NS_ANDROID)
    except:
        pass

def is_tracker(name):
    if not name: return False
    return any(t in name for t in TRACKERS)

# Fonction récursive pour supprimer les éléments imbriqués (ex: meta-data dans service)
def remove_trackers_recursive(element, parent=None):
    # On travaille sur une copie de la liste pour pouvoir supprimer en itérant
    for child in list(element):

        # 1. Vérification du Nom (Attribut android:name)
        # ElementTree gère les namespaces avec des accolades {url}nom
        name = child.get(f'{{{NS_ANDROID}}}name', '')

        should_remove = False

        # Logique Traqueurs (Activity, Service, Receiver, Meta-data)
        if child.tag in ['activity', 'service', 'receiver', 'provider', 'meta-data']:
            if is_tracker(name):
                should_remove = True
                print(f"Python: Suppression Tracker -> {name}")

        # Logique Permissions
        if child.tag == 'uses-permission':
            if name in PERMISSIONS_TO_REMOVE:
                should_remove = True
                print(f"Python: Suppression Permission -> {name}")

        if should_remove:
            element.remove(child) # Suppression sûre car on est dans le parent
        else:
            # Si on ne supprime pas, on descend voir les enfants (récursivité)
            remove_trackers_recursive(child, element)

def process_manifest_content(xml_content_string):
    """
    Cette fonction sera appelée depuis Kotlin.
    Elle prend le XML en String -> Patch -> Retourne le XML en String.
    """
    try:
        register_namespaces()

        # Parsing du texte reçu de Kotlin
        tree = ET.ElementTree(ET.fromstring(xml_content_string))
        root = tree.getroot()

        # Lancement du nettoyage récursif
        remove_trackers_recursive(root)

        # Conversion retour en String
        output = io.BytesIO()
        tree.write(output, encoding='utf-8', xml_declaration=True)
        return output.getvalue().decode('utf-8')

    except Exception as e:
        return f"ERROR_PYTHON: {str(e)}"